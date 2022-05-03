package info.arhome.home.k8s.nsd4k;

public class PrettyException extends RuntimeException {
    public PrettyException(String msg, Throwable e) {
        super(msg, e);
    }

    public PrettyException(String msg) {
        super(msg);
    }

    @Override
    public String toString() {
        if (getCause() != null)
            return getMessage() + ": " + getCause().toString();
        else
            return getMessage();
    }
}
