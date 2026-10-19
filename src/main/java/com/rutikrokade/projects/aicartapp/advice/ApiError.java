package com.rutikrokade.projects.aicartapp.advice;

import java.util.List;

import com.rutikrokade.projects.aicartapp.dto.FieldErrorDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiError {

    private int status;

    private String code;

    private String message;

    private List<FieldErrorDto> errors;
}
