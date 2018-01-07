package jitstatic.source;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import jitstatic.FileObjectIdStore;
import jitstatic.hosted.InputStreamHolder;

public class SourceInfo {

	private final FileObjectIdStore fileInfo;
	private final InputStreamHolder inputStreamHolder;

	public SourceInfo(final FileObjectIdStore fileInfo, final InputStreamHolder inputStreamHolder) {
		this.fileInfo = Objects.requireNonNull(fileInfo);
		this.inputStreamHolder = Objects.requireNonNull(inputStreamHolder);
	}

	public InputStream getInputStream() throws IOException {
		if (inputStreamHolder.isPresent()) {
			try {
				return inputStreamHolder.inputStream();
			} catch (final IOException e) {
				throw new IOException("Error reading " + fileInfo.getFileName(), e);
			}
		} else {
			throw new RuntimeException(inputStreamHolder.exception());
		}
	}

	public String getVersion() {
		return fileInfo.getObjectId().name();
	}

}
