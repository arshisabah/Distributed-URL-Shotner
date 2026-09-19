package com.shortener.url.port.in;

import com.shortener.url.application.dto.RedirectContext;
import com.shortener.url.application.dto.ResolveUrlResult;

public interface ResolveUrlUseCase {
    ResolveUrlResult resolve(String shortCode, RedirectContext context);
}
