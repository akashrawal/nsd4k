package info.arhome.home.k8s.nsd4k;

import java.io.IOException;
//import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

public class CA {
    public static final String CA_CERT_FILE = "cacert.pem";
    public static final String CA_KEY_FILE = "cakey.pem";
    public static final String EXT_FILE = "ext.cnf";
    public static final String CERT_FILE = "cert.pem";
    public static final String CSR_FILE = "cert.csr";
    public static final String KEY_FILE = "key.pem";
    //private static final String COMBINED_FILE = "combined.pem";

    public static class Cert {
        private final FileTime generationTime;

        private final Path basePath;

        private Cert(Path _basePath) throws IOException {
            basePath = _basePath;

            generationTime = Files.readAttributes(basePath.resolve(CERT_FILE), BasicFileAttributes.class).lastModifiedTime();
        }

        public Path getCertificatePem() {
            return basePath.resolve(CERT_FILE);
        }

        public Path getPrivateKeyPem() {
            return basePath.resolve(KEY_FILE);
        }

        public FileTime getGenerationTime() {
            return generationTime;
        }

        /*
        public Path getCombinedPem() throws IOException {
            Path result = basePath.resolve(COMBINED_FILE);
            if (! Files.exists(result)) {
                try(OutputStream o = new BufferedOutputStream(Files.newOutputStream(result))) {
                    Files.copy(basePath.resolve(CERT_FILE), o);
                    Files.copy(basePath.resolve(KEY_FILE), o);
                }
            }
            return result;
        }
        */
    }

    final ConfigDto config;
    final Path basedir;
    final Path cacert;
    final Path cakey;

    private int run(String... args) throws IOException {
        try {
            Process p = new ProcessBuilder(args).inheritIO().directory(basedir.toFile()).start();
            return p.waitFor();
        } catch (Exception e) {
            throw new IOException("Unable to run command " + Arrays.toString(args));
        }
    }

    private void runOrDie(String... args) throws IOException {
        int status = run(args);
        if (status != 0)
            throw new IOException("Command returned " + status + " exit status: " + Arrays.toString(args));
    }

    private String makeSubject(String name) {
        return config.distinguishedNamePrefix + "/CN=" + name;
    }

    public CA(ConfigDto _config) throws IOException {
        config = _config;

        basedir = Paths.get(config.datadir, "ca").toAbsolutePath();

        Files.createDirectories(basedir.resolve("public"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        Files.createDirectories(basedir.resolve("private"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createDirectories(basedir.resolve("apps"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

        cacert = basedir.resolve("public").resolve(CA_CERT_FILE);
        cakey = basedir.resolve("private").resolve(CA_KEY_FILE);

        //Generate CA certificate if it does not exist
        if (! Files.exists(cacert)) {
            runOrDie("openssl", "req", "-new", "-x509", "-newkey", "rsa:4096", "-days", "1095",
                    "-nodes", "-subj", makeSubject(config.caCommonName),
                    "-keyout", cakey.toString(), "-out", cacert.toString());
        }
    }

    public Cert makeCert(String name) throws IOException {
        //Make the application directory
        Path appdir = basedir.resolve("apps").resolve(name);
        runOrDie("rm", "-rf", appdir.toString());
        Files.createDirectories(appdir);

        //subjectAltName
        String primaryName = null;
        StringBuilder sanBuilder = new StringBuilder();
        for (String suffix : config.domains) {
            if (!sanBuilder.isEmpty())
                sanBuilder.append(",");
            String fullName = name;
            if (! suffix.equals(""))
                fullName = name + "." + suffix;
            sanBuilder.append("DNS:").append(fullName);
            if (primaryName == null)
                primaryName = fullName;
        }
        assert(primaryName != null);
        String san = sanBuilder.toString();

        //CSR
        runOrDie("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes",
                "-subj", makeSubject(primaryName), "-addext", "subjectAltName=" + san,
                "-keyout", appdir.resolve(KEY_FILE).toString(), "-out", appdir.resolve(CSR_FILE).toString());

        //Signing
        Files.write(appdir.resolve(EXT_FILE), Arrays.asList("[EXTENSIONS]", "subjectAltName = " + san));
        runOrDie("openssl", "x509", "-req", "-in", appdir.resolve(CSR_FILE).toString(),
            "-days", "60", "-extfile", appdir.resolve(EXT_FILE).toString(), "-extensions=EXTENSIONS",
                "-CA", cacert.toString(), "-CAkey", cakey.toString(), "-CAcreateserial",
                "-out", appdir.resolve(CERT_FILE).toString());
        Files.delete(appdir.resolve(EXT_FILE));

        return new Cert(appdir);
    }

    public Cert getCert(String name) throws IOException {
        Path appdir = basedir.resolve("apps").resolve("name");

        try {
            Cert cert = new Cert(appdir);

            //Check if certificate is too old
            if (cert.getGenerationTime().toInstant().plus(Duration.ofDays(30)).isAfter(Instant.now())) {
                //Recent enough
                return cert;
            }
        } catch(IOException e) {
            //Swallow, certificate does not exist
        }

        return makeCert(name);
    }
}
