package com.zxl.agi.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ConsolidationResult {

    /**
     * 去重删除的条目数
     */
    public int deduped;

    /**
     * 合并的条目数
     */
    public int merged;

    /**
     * 过期删除的条目数
     */
    public int expired;

    /**
     * 需要从 PG 删除的 ID 列表
     */
    public List<Long> deleteFromDB = new ArrayList<>();

    /**
     * 需要在 PG 更新的条目列表
     */
    public List<LongTermMemoryItem> updateInDB = new ArrayList<>();
}
