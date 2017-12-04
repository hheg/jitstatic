package jitstatic.util;

public class Pair<T, U> {
	private final T left;
	private final U right;

	public Pair() {
		this.left = null;
		this.right = null;
	}
	
	public Pair(final T t, final U u) {
		this.left = t;
		this.right = u;
	}

	public T getLeft() {
		return left;
	}

	public U getRight() {
		return right;
	}
	
	public boolean isPresent(){
		return left != null && right != null;
	}
}