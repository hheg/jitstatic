package jitstatic.remote;

public class RepositoryIsMissingIntendedBranch extends RuntimeException {
	public RepositoryIsMissingIntendedBranch(String msg) {
		super(msg);
	}

	private static final long serialVersionUID = -7606348017682123021L;
	/*
	 * (non-Javadoc) Not caring about the stack here, just for signaling an error;
	 * @see java.lang.Throwable#fillInStackTrace()
	 */
	@Override
	public synchronized Throwable fillInStackTrace() {
		return this;
	}
}
