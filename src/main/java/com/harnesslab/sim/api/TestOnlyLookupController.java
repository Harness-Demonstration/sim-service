package com.harnesslab.sim.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** TEST ONLY: a deliberate SQL injection that CodeQL must report; this pull request is never merged. */
@RestController
public class TestOnlyLookupController {

    private final JdbcTemplate jdbc;

    public TestOnlyLookupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/test-only/sims")
    public List<Map<String, Object>> find(@RequestParam String iccid) {
        return jdbc.queryForList("select * from sim where iccid = '" + iccid + "'");
    }
}
