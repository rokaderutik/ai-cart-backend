package com.rutikrokade.projects.aicartapp.dto;

import lombok.Data;

@Data
public class RetrievalRequestDto {

    public enum Target { MENU, GUIDE, BOTH }

    private Target target;

    private String query;

    private String menuQuery;

    private String guideQuery;

    private RetrievalFiltersDto filters;
}
