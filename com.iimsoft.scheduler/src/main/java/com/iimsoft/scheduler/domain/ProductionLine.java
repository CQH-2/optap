package com.iimsoft.scheduler.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ProductionLine {
    private String code;
    private List<Router> supportedRouters = new ArrayList<>();

    public ProductionLine() {}

    public ProductionLine(String code) {
        this.code = code;
    }

    public String getCode() { return code; }
    public List<Router> getSupportedRouters() { return supportedRouters; }

    public void setCode(String code) { this.code = code; }
    public void setSupportedRouters(List<Router> supportedRouters) { this.supportedRouters = supportedRouters; }

    public boolean supports(Router router) {
        return router != null && supportedRouters.contains(router);
    }

    @Override
    public String toString() {
        return code;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductionLine)) return false;
        ProductionLine that = (ProductionLine) o;
        return Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}