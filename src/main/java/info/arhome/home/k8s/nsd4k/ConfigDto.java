package info.arhome.home.k8s.nsd4k;

public class ConfigDto {
    public String datadir;
    public String[] domains;
    public String distinguishedNamePrefix;
    public String caCommonName;

    public static class ListeningSocket {
        public String addr;
        public int port;
    }
    public ListeningSocket[] dnsListen;

    public String[] privilegedNamespaces;
}

