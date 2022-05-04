package info.arhome.home.k8s.nsd4k;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.TSIGRecord;
import org.xbill.DNS.Type;
import org.xbill.DNS.ZoneTransferException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DnsServer {
    static final Logger log = LoggerFactory.getLogger(DnsServer.class);

    final ConfigDto config;
    final DnsDB dnsDB;

    private static String addrport(InetAddress addr, int port) {
        return addr.getHostAddress() + "#" + port;
    }

    public DnsServer(ConfigDto _config, DnsDB _dnsDB) throws IOException, ZoneTransferException {
        config = _config;
        dnsDB = _dnsDB;

        for (ConfigDto.ListeningSocket bindaddrs : config.dnsListen)
            addListener(bindaddrs.addr, bindaddrs.port);
        if (config.dnsListen.length == 0)
            addListener("127.0.0.1", 8053);

        log.info("running");
    }

    private void addListener(String addrStr, int port) throws UnknownHostException {
        InetAddress addr = InetAddress.getByName(addrStr);
        addUDP(addr, port);
        addTCP(addr, port);
        log.info("listening on {}", addrport(addr, port));
    }

    private byte addAnswer(Message response, Name name, int type, int dclass) {
        byte rcode = Rcode.NOERROR;

        //Find out the query string
        String queryString = name.toString(true);
        String lookupString = null;
        List<String> aRecords = null;
        boolean nameExists = false;
        for (String oneDomain : config.domains) {
            String suffix = "." + oneDomain;
            if (queryString.endsWith(suffix)) {
                lookupString = queryString.substring(0, queryString.length() - suffix.length());
                break;
            }
        }
        if (lookupString != null) {
            synchronized (dnsDB) {
                aRecords = dnsDB.aRecords.get(lookupString);
            }
        }

        if (aRecords != null) {
            try {
                //Successful lookup
                List<Record> records = new ArrayList<>();

                for (String addr : aRecords) {
                    int recordType = Type.A;
                    if (addr.contains(":"))
                        recordType = Type.AAAA;
                    if (type == recordType || type == Type.ANY) {
                        records.add(Record.fromString(name, type, dclass, 60, addr, null));
                    }
                }

                for (Record r : records) {
                    response.addRecord(r, Section.ANSWER);
                }
                nameExists = true;
            } catch (Exception e) {
                log.error("addAnswer: exception", e);
            }
        }

        if (!nameExists) {
            //NXDOMAIN
            response.getHeader().setRcode(Rcode.NXDOMAIN);
            rcode = Rcode.NXDOMAIN;
        }

        return rcode;
    }

    private byte[] generateReply(Message query, byte[] in, Socket s) {
        Header header;
        int maxLength;

        header = query.getHeader();
        if (header.getFlag(Flags.QR)) {
            return null;
        }
        if (header.getRcode() != Rcode.NOERROR) {
            return errorMessage(query, Rcode.FORMERR);
        }
        if (header.getOpcode() != Opcode.QUERY) {
            return errorMessage(query, Rcode.NOTIMP);
        }

        org.xbill.DNS.Record queryRecord = query.getQuestion();

        //TSIG not supported
        TSIGRecord queryTSIG = query.getTSIG();
        if (queryTSIG != null) {
            return formerrMessage(in);
        }

        OPTRecord queryOPT = query.getOPT();
        if (s != null) {
            maxLength = 65535;
        } else if (queryOPT != null) {
            maxLength = Math.max(queryOPT.getPayloadSize(), 512);
        } else {
            maxLength = 512;
        }

        Message response = new Message(query.getHeader().getID());
        response.getHeader().setFlag(Flags.QR);
        if (query.getHeader().getFlag(Flags.RD)) {
            response.getHeader().setFlag(Flags.RD);
        }
        response.addRecord(queryRecord, Section.QUESTION);

        Name name = queryRecord.getName();
        int type = queryRecord.getType();
        int dclass = queryRecord.getDClass();

        if (!Type.isRR(type) && type != Type.ANY) {
            return errorMessage(query, Rcode.NOTIMP);
        }

        byte rcode = addAnswer(response, name, type, dclass);
        if (rcode != Rcode.NOERROR && rcode != Rcode.NXDOMAIN) {
            return errorMessage(query, rcode);
        }

        if (queryOPT != null) {
            OPTRecord opt = new OPTRecord((short) 4096, rcode, (byte) 0, 0);
            response.addRecord(opt, Section.ADDITIONAL);
        }

        return response.toWire(maxLength);
    }

    private byte[] buildErrorMessage(Header header, int rcode, Record question) {
        Message response = new Message();
        response.setHeader(header);
        for (int i = 0; i < 4; i++) {
            response.removeAllRecords(i);
        }
        if (rcode == Rcode.SERVFAIL) {
            response.addRecord(question, Section.QUESTION);
        }
        header.setRcode(rcode);
        return response.toWire();
    }

    private byte[] formerrMessage(byte[] in) {
        Header header;
        try {
            header = new Header(in);
        } catch (IOException e) {
            return null;
        }
        return buildErrorMessage(header, Rcode.FORMERR, null);
    }

    private byte[] errorMessage(Message query, int rcode) {
        return buildErrorMessage(query.getHeader(), rcode, query.getQuestion());
    }

    private void serveTCPConnection(Socket s) {
        InetAddress addr = s.getLocalAddress();
        int port = s.getLocalPort();

        try (InputStream is = s.getInputStream()) {
            int inLength;
            DataInputStream dataIn;
            DataOutputStream dataOut;
            byte[] in;

            dataIn = new DataInputStream(is);
            inLength = dataIn.readUnsignedShort();
            in = new byte[inLength];
            dataIn.readFully(in);

            Message query;
            byte[] response;
            try {
                query = new Message(in);
                response = generateReply(query, in, s);
                if (response == null) {
                    return;
                }
            } catch (IOException e) {
                response = formerrMessage(in);
            }
            dataOut = new DataOutputStream(s.getOutputStream());
            assert response != null;
            dataOut.writeShort(response.length);
            dataOut.write(response);
        } catch (IOException e) {
            log.info( "serveTCPConnection({}): exception", addrport(addr, port), e);
        }
    }

    private void serveTCP(InetAddress addr, int port) {
        try (ServerSocket sock = new ServerSocket(port, 128, addr)) {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 10,
                    5, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10));

            while (true) {
                final Socket s = sock.accept();
                s.setSoTimeout(15);
                executor.execute(() -> serveTCPConnection(s));
            }
        } catch (IOException e) {
            log.info( "serveTCP({}): exception", addrport(addr, port), e);
        }
    }

    private void serveUDP(InetAddress addr, int port) {
        try (DatagramSocket sock = new DatagramSocket(port, addr)) {
            final short udpLength = 512;
            byte[] in = new byte[udpLength];
            DatagramPacket indp = new DatagramPacket(in, in.length);
            DatagramPacket outdp = null;
            while (true) {
                try {
                    indp.setLength(in.length);
                    try {
                        sock.receive(indp);
                    } catch (InterruptedIOException e) {
                        continue;
                    }
                    Message query;
                    byte[] response;
                    try {
                        query = new Message(in);
                        response = generateReply(query, in, null);
                        if (response == null) {
                            continue;
                        }
                    } catch (IOException e) {
                        response = formerrMessage(in);
                    }
                    assert response != null;
                    if (outdp == null) {
                        outdp = new DatagramPacket(response, response.length, indp.getAddress(), indp.getPort());
                    } else {
                        outdp.setData(response);
                        outdp.setLength(response.length);
                        outdp.setAddress(indp.getAddress());
                        outdp.setPort(indp.getPort());
                    }
                    sock.send(outdp);
                } catch (Exception e) {
                    log.info( "serveUDP({}): connection: exception", addrport(addr, port), e);
                }
            }
        } catch (IOException e) {
            log.info( "serveUDP({}): exception", addrport(addr, port), e);
        }
    }

    private void addTCP(final InetAddress addr, final int port) {
        Thread t = new Thread(() -> serveTCP(addr, port));
        t.start();
    }

    private void addUDP(final InetAddress addr, final int port) {
        Thread t = new Thread(() -> serveUDP(addr, port));
        t.start();
    }

}
