package com.freezhub.organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /**
     * The largest lead time any organization has configured (FZ-047).
     *
     * <p>Used to bound the starting-soon sweep: nothing outside this horizon can be due
     * for any tenant, so the sweep never has to consider every future restriction ever
     * created. Null when there are no organizations at all.
     */
    @Query("select max(o.startingSoonLeadTimeMinutes) from Organization o")
    Integer maxStartingSoonLeadTimeMinutes();

}
