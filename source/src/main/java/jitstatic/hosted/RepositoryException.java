package jitstatic.hosted;

public class RepositoryException extends Exception {

	private static final long serialVersionUID = 1L;

	public RepositoryException(final String msg, final Exception e) {
		super(msg,e);
	}
	
	@Override
	public Throwable fillInStackTrace() {
		return this;
	}
}
