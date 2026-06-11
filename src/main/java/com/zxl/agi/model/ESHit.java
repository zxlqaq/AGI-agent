package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ES BM25召回结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ESHit {

    private Long pgId;

    private Double score;

}
