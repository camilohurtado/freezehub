package com.freezhub.restriction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The scope matching rule (01-domain.md § Scope matching semantics), which `FZ-051`
 * evaluates.
 *
 * <p>No Spring context: this is the rule the whole product exists to enforce, and it
 * deserves to be readable and provable on its own rather than only through HTTP. The
 * worked examples below are the ones written into `01-domain.md` and `04-api.md`.
 */
class RestrictionScopeMatchingTest {

    private static final Long PAYMENTS_API = 1L;
    private static final Long CHECKOUT_API = 2L;
    private static final Long PRODUCTION = 10L;
    private static final Long STAGING = 11L;
    private static final Long PAYMENTS_TEAM = 100L;
    private static final Long PLATFORM_TEAM = 101L;

    private ChangeRestriction scopedTo(Set<Long> teamIds, Set<Long> applicationIds, Set<Long> environmentIds) {
        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
        return new ChangeRestriction(1L, "Freeze", null, "Revenue-critical period",
                RestrictionLevel.HARD_FREEZE, startsAt, startsAt.plus(1, ChronoUnit.DAYS), 1L,
                teamIds, applicationIds, environmentIds);
    }

    @Test
    void anEmptyDimensionIsAWildcardRatherThanAnEmptySet() {
        // "Freeze every deployment to production" is expressed by naming only an
        // environment; the application dimension places no constraint at all.
        ChangeRestriction productionFreeze = scopedTo(Set.of(), Set.of(), Set.of(PRODUCTION));

        assertThat(productionFreeze.covers(PAYMENTS_API, PRODUCTION, Set.of())).isTrue();
        assertThat(productionFreeze.covers(CHECKOUT_API, PRODUCTION, Set.of())).isTrue();
        assertThat(productionFreeze.covers(PAYMENTS_API, STAGING, Set.of())).isFalse();
    }

    @Test
    void listedResourcesWithinADimensionAreOred() {
        ChangeRestriction either = scopedTo(Set.of(), Set.of(PAYMENTS_API, CHECKOUT_API), Set.of(PRODUCTION));

        assertThat(either.covers(PAYMENTS_API, PRODUCTION, Set.of())).isTrue();
        assertThat(either.covers(CHECKOUT_API, PRODUCTION, Set.of())).isTrue();
        assertThat(either.covers(99L, PRODUCTION, Set.of())).isFalse();
    }

    @Test
    void dimensionsAreAndedTogether() {
        // Both must hold: the right application in the wrong environment is not covered.
        ChangeRestriction checkoutInProduction = scopedTo(Set.of(), Set.of(CHECKOUT_API), Set.of(PRODUCTION));

        assertThat(checkoutInProduction.covers(CHECKOUT_API, PRODUCTION, Set.of())).isTrue();
        assertThat(checkoutInProduction.covers(CHECKOUT_API, STAGING, Set.of())).isFalse();
        assertThat(checkoutInProduction.covers(PAYMENTS_API, PRODUCTION, Set.of())).isFalse();
    }

    @Test
    void aTeamScopeCoversWhateverThatTeamOwns() {
        ChangeRestriction paymentsTeamFreeze = scopedTo(Set.of(PAYMENTS_TEAM), Set.of(), Set.of());

        assertThat(paymentsTeamFreeze.covers(PAYMENTS_API, PRODUCTION, Set.of(PAYMENTS_TEAM))).isTrue();
        assertThat(paymentsTeamFreeze.covers(PAYMENTS_API, STAGING, Set.of(PAYMENTS_TEAM))).isTrue();
        assertThat(paymentsTeamFreeze.covers(CHECKOUT_API, PRODUCTION, Set.of(PLATFORM_TEAM))).isFalse();
    }

    @Test
    void anApplicationOwnedByNoTeamIsNotCoveredByATeamScope() {
        // The empty-wildcard rule applies to the *restriction's* dimensions, not to the
        // deployment's context: an unowned application does not match every team scope.
        ChangeRestriction paymentsTeamFreeze = scopedTo(Set.of(PAYMENTS_TEAM), Set.of(), Set.of());

        assertThat(paymentsTeamFreeze.covers(PAYMENTS_API, PRODUCTION, Set.of())).isFalse();
    }

    @Test
    void anApplicationInAnyOneOfTheListedTeamsIsCovered() {
        ChangeRestriction eitherTeam = scopedTo(Set.of(PAYMENTS_TEAM, PLATFORM_TEAM), Set.of(), Set.of());

        assertThat(eitherTeam.covers(PAYMENTS_API, PRODUCTION, Set.of(PLATFORM_TEAM))).isTrue();
        assertThat(eitherTeam.covers(PAYMENTS_API, PRODUCTION, Set.of(PLATFORM_TEAM, 999L))).isTrue();
    }

    @Test
    void namingATeamAndAnApplicationNarrowsRatherThanWidens() {
        // Teams and applications are separate dimensions, so this reads "checkout-api,
        // and only while it belongs to Payments" — not "checkout-api or anything Payments
        // owns". Getting this backwards would silently widen every such freeze.
        ChangeRestriction narrowed = scopedTo(Set.of(PAYMENTS_TEAM), Set.of(CHECKOUT_API), Set.of());

        assertThat(narrowed.covers(CHECKOUT_API, PRODUCTION, Set.of(PAYMENTS_TEAM))).isTrue();
        assertThat(narrowed.covers(CHECKOUT_API, PRODUCTION, Set.of(PLATFORM_TEAM))).isFalse();
        assertThat(narrowed.covers(PAYMENTS_API, PRODUCTION, Set.of(PAYMENTS_TEAM))).isFalse();
    }

    @Test
    void aScopeNamingATeamAndAnApplicationOutsideItCoversNothing() {
        // Accepted rather than rejected at creation, because membership is mutable — a
        // scope that matches nothing today may match tomorrow.
        ChangeRestriction impossibleToday = scopedTo(Set.of(PAYMENTS_TEAM), Set.of(CHECKOUT_API), Set.of());

        assertThat(impossibleToday.covers(CHECKOUT_API, PRODUCTION, Set.of(PLATFORM_TEAM))).isFalse();
        // ...and tomorrow, once checkout-api joins Payments:
        assertThat(impossibleToday.covers(CHECKOUT_API, PRODUCTION, Set.of(PLATFORM_TEAM, PAYMENTS_TEAM)))
                .isTrue();
    }

    @Test
    void allThreeDimensionsTogetherMustAllHold() {
        ChangeRestriction fullyQualified =
                scopedTo(Set.of(PAYMENTS_TEAM), Set.of(PAYMENTS_API), Set.of(PRODUCTION));

        assertThat(fullyQualified.covers(PAYMENTS_API, PRODUCTION, Set.of(PAYMENTS_TEAM))).isTrue();
        assertThat(fullyQualified.covers(PAYMENTS_API, STAGING, Set.of(PAYMENTS_TEAM))).isFalse();
        assertThat(fullyQualified.covers(CHECKOUT_API, PRODUCTION, Set.of(PAYMENTS_TEAM))).isFalse();
        assertThat(fullyQualified.covers(PAYMENTS_API, PRODUCTION, Set.of(PLATFORM_TEAM))).isFalse();
    }

}
