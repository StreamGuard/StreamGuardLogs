package com.example.school.sptech.dto;
public class IssueFieldsDto {

    private ProjectDto project;
    private String summary;
    private IssueTypeDto issuetype;

    // Construtor vazio (necessário se o Jackson for desserializar)
    public IssueFieldsDto() {
    }

    public IssueFieldsDto(ProjectDto project, String summary, IssueTypeDto issuetype) {
        this.project = project;
        this.summary = summary;
        this.issuetype = issuetype;
    }

    public ProjectDto getProject() {
        return project;
    }

    public void setProject(ProjectDto project) {
        this.project = project;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public IssueTypeDto getIssuetype() {
        return issuetype;
    }

    public void setIssuetype(IssueTypeDto issuetype) {
        this.issuetype = issuetype;
    }
}