package com.yucareux.tellus.world.data.cover;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;

class HttpSeekableByteChannel implements SeekableByteChannel {
    private final URI uri;
    private final long size;
    private long position;
    private boolean open = true;

    HttpSeekableByteChannel(URI uri) throws IOException {
        this.uri = uri;
        this.size = fetchSize();
        this.position = 0;
    }

    private long fetchSize() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("HEAD");
        connection.connect(); // Ensure we connect to check status
        if (connection.getResponseCode() != 200) {
            throw new IOException("Failed to fetch size: " + connection.getResponseMessage());
        }
        long length = connection.getContentLengthLong();
        if (length == -1) {
            throw new IOException("Content length unknown");
        }
        return length;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (!open)
            throw new ClosedChannelException();
        if (position >= size)
            return -1;

        int wanted = dst.remaining();
        if (wanted <= 0)
            return 0;

        long end = Math.min(position + wanted - 1, size - 1);
        long actualWanted = end - position + 1;

        URL url = uri.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Range", "bytes=" + position + "-" + end);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);

        int responseCode = connection.getResponseCode();
        if (responseCode != 206 && responseCode != 200) {
            throw new IOException("Failed to read range: " + responseCode);
        }

        try (InputStream in = connection.getInputStream()) {
            byte[] buffer = new byte[8192];
            int totalRead = 0;
            while (totalRead < actualWanted) {
                int bytesRead = in.read(buffer, 0, Math.min(buffer.length, (int) (actualWanted - totalRead)));
                if (bytesRead < 0)
                    break;
                dst.put(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }
            position += totalRead;
            return totalRead;
        }
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        throw new IOException("Write not supported");
    }

    @Override
    public long position() throws IOException {
        if (!open)
            throw new ClosedChannelException();
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        if (!open)
            throw new ClosedChannelException();
        if (newPosition < 0)
            throw new IllegalArgumentException("Negative position");
        this.position = newPosition;
        return this;
    }

    @Override
    public long size() throws IOException {
        if (!open)
            throw new ClosedChannelException();
        return size;
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        throw new IOException("Truncate not supported");
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        this.open = false;
    }
}
