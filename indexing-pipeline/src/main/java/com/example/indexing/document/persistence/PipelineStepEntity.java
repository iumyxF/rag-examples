package com.example.indexing.document.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;

@TableName("rag_pipeline_step")
public class PipelineStepEntity {
    @TableId(type = IdType.INPUT)
    public String versionId;
    public String stepCode;
    public String status;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public Long durationMs;
    public int itemCount;
    public String outputSummary;
    public String errorMessage;
}
