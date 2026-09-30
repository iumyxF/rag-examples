package com.example.indexing.document.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("rag_document")
public class DocumentEntity {
    @TableId
    public String id;
    public String fileName;
    public String contentHash;
    public String mediaType;
    public String storagePath;
    public String status;
    public String activeVersionId;
    public String workingVersionId;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
