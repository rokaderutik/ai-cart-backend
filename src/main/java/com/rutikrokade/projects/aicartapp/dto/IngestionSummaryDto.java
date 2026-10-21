package com.rutikrokade.projects.aicartapp.dto;

import lombok.Value;

@Value
public class IngestionSummaryDto {

    String mode;

    long revision;

    int menuInserted;

    int menuUpdated;

    int menuRetired;

    int documentsEmbedded;

    int documentsSkipped;

    int documentsDeleted;

    int metadataUpdated;

    long durationMillis;
}
