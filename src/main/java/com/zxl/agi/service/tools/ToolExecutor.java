package com.zxl.agi.service.tools;

import java.util.Map;

@FunctionalInterface
public interface ToolExecutor {

    String execute(Map<String, Object> params) throws Exception;
}
