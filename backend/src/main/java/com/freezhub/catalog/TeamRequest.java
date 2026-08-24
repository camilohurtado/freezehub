package com.freezhub.catalog;

import jakarta.validation.constraints.NotBlank;

public record TeamRequest(@NotBlank String name) {
}
