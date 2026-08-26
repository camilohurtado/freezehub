package com.freezhub.catalog;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deleting a catalog entry that a change restriction still references.
 *
 * <p>The restriction scope tables reference catalog rows with non-cascading foreign keys
 * on purpose (see 03-data-model.md): cascading would silently shrink a restriction's
 * scope, possibly emptying it, and silently stop blocking deployments it was created to
 * block. So the database refuses the delete — this turns that refusal into a `409` the
 * caller can act on instead of an unhandled `500`.
 *
 * <p>The {@code flush} matters. Without it the DELETE is deferred to commit, the
 * violation is raised after the service method has returned, and the caller gets a 500
 * regardless of any catch here.
 *
 * <p>Enforcement stays in the database rather than a pre-check, which keeps the
 * dependency direction intact: {@code restriction} may depend on {@code catalog}, not the
 * other way round.
 */
final class CatalogDeletion {

    private CatalogDeletion() {
    }

    static <T> void deleteOrReportInUse(T entity, JpaRepository<T, Long> repository, String resourceName) {
        try {
            repository.delete(entity);
            repository.flush();
        } catch (DataIntegrityViolationException inUse) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This " + resourceName + " is referenced by one or more change restrictions "
                            + "and cannot be deleted; remove it from their scope first");
        }
    }

}
