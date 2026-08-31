package com.freezhub.shared.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Applies {@link SecretProtector} to a column, so a field holding secret material is
 * encrypted on the way out and decrypted on the way in (FZ-049).
 *
 * <p>A converter rather than explicit calls in services: the entity's field stays
 * plaintext in Java, so nothing that reads or validates it needed changing, and there is
 * no code path that can forget to encrypt. A Spring bean as well as a JPA converter,
 * which Hibernate resolves through Boot's {@code SpringBeanContainer} — that is what lets
 * it be given the protector.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final SecretProtector secretProtector;

    public EncryptedStringConverter(SecretProtector secretProtector) {
        this.secretProtector = secretProtector;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return secretProtector.protect(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return secretProtector.reveal(dbData);
    }

}
