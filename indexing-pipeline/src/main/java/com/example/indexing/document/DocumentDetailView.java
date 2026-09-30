package com.example.indexing.document;

public record DocumentDetailView(
        DocumentView document, VersionView activeVersion, VersionView workingVersion) {
}
