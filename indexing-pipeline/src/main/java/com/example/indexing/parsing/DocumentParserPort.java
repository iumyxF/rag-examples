package com.example.indexing.parsing;

import java.io.InputStream;

public interface DocumentParserPort {
    NormalizedDocument parse(InputStream input, String fileName, String mediaType);
}
