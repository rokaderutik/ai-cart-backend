package com.rutikrokade.projects.aicartapp.service;

import com.rutikrokade.projects.aicartapp.dto.RetrievalRequestDto;
import com.rutikrokade.projects.aicartapp.dto.RetrievalResponseDto;

public interface RetrievalService {

    RetrievalResponseDto retrieve(RetrievalRequestDto request);
}
