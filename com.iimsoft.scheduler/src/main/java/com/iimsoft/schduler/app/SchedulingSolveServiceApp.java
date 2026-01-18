package com.iimsoft.schduler.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iimsoft.schduler.api.dto.SolveRequest;
import com.iimsoft.schduler.api.dto.SolveResponse;
import com.iimsoft.schduler.service.SchedulingSolveService;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 统一入口：从 JSON 请求调用 SchedulingSolveService。
 *
 * 用法：
 * - 读取文件：mvn exec:java -Dexec.args=path/to/request.json
 * - 读取 stdin：mvn exec:java -Dexec.args=- < request.json
 */
public class SchedulingSolveServiceApp {

    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0 || args[0] == null || args[0].isBlank()) {
            System.err.println("缺少参数：SolveRequest JSON 文件路径，或 '-' 代表从 stdin 读取。\n" +
                    "示例：mvn exec:java -Dexec.args=request.json");
            System.exit(2);
            return;
        }

        ObjectMapper mapper = new ObjectMapper();

        SolveRequest request;
        String input = args[0].trim();
        if ("-".equals(input)) {
            try (InputStream in = System.in) {
                request = mapper.readValue(in, SolveRequest.class);
            }
        } else {
            Path path = Path.of(input);
            if (!Files.exists(path) || Files.isDirectory(path)) {
                System.err.println("请求文件不存在或是目录：" + path.toAbsolutePath());
                System.exit(2);
                return;
            }
            request = mapper.readValue(new File(path.toString()), SolveRequest.class);
        }

        SchedulingSolveService service = new SchedulingSolveService();
        SolveResponse response = service.solve(request);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
        System.out.println(json);
    }
}
