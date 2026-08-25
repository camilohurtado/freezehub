package com.freezhub.restriction;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeRestrictionRepository extends JpaRepository<ChangeRestriction, Long> {

    /**
     * Ordered soonest-first, tie-broken by id so the ordering is total and the result is
     * deterministic across calls. Scope collections are LAZY and deliberately not touched
     * here: the list is a summary, so listing costs one query regardless of row count.
     */
    List<ChangeRestriction> findAllByOrganizationIdOrderByStartsAtAscIdAsc(Long organizationId);

    List<ChangeRestriction> findAllByOrganizationIdAndStatusInOrderByStartsAtAscIdAsc(
            Long organizationId, Collection<RestrictionStatus> statuses);

}
