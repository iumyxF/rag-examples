package com.example.indexing.document.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("rag_document_version")
public class DocumentVersionEntity {
    @TableId
    public String id;
    public String documentId;
    public String status;
    public String sourceVersionId;
    public int chunkCount;
    public int entityCount;
    public int relationCount;
    public String errorMessage;
    public LocalDateTime createdAt;
    public LocalDateTime activatedAt;
}
