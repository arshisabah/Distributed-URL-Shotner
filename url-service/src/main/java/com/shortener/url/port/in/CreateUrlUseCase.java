package com.shortener.url.port.in;

import com.shortener.url.application.dto.CreateUrlCommand;
import com.shortener.url.application.dto.CreateUrlResult;

public interface CreateUrlUseCase {
    CreateUrlResult createUrl(CreateUrlCommand command, Long userId);
}
