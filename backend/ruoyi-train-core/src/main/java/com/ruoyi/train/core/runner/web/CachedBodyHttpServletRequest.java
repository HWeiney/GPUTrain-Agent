package com.ruoyi.train.core.runner.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper
{
    public static final String BODY_ATTRIBUTE = "gputrain.runnerRawBody";
    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException
    {
        super(request);
        this.body = request.getInputStream().readAllBytes();
        setAttribute(BODY_ATTRIBUTE, body.clone());
    }

    @Override
    public ServletInputStream getInputStream()
    {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream()
        {
            @Override
            public int read() { return input.read(); }

            @Override
            public boolean isFinished() { return input.available() == 0; }

            @Override
            public boolean isReady() { return true; }

            @Override
            public void setReadListener(ReadListener listener)
            {
                throw new UnsupportedOperationException("Async reads are not supported");
            }
        };
    }

    @Override
    public BufferedReader getReader()
    {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() { return body.length; }

    @Override
    public long getContentLengthLong() { return body.length; }
}
