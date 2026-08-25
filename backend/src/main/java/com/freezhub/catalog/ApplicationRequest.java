package com.freezhub.catalog;

import jakarta.validation.constraints.NotBlank;

public record ApplicationRequest(@NotBlank String name) {
}
