# FreezeHub — Validation Plan

## Purpose

What has to be true before this product is worth selling, what it costs to find out, and what is deliberately not built until it is.

Written because "what infrastructure and company setup do I need to launch?" has a far smaller answer than it looks, and almost every expensive answer to it is premature. This document is the smaller answer.

It is a plan, not a record: **nothing in §2 has been done.**

## 1. What is actually being validated

**Not "will anyone pay".** The riskiest assumption in this product is narrower and comes first:

> **Will a team wire `freeze-check` into a pipeline and leave it there?**

Everything rests on that. A freeze is enforced only because a pipeline asks (`D-24`); an application is worth registering only because unregistered ones block (`D-14`); the deployment console, the audit trail and the entire pricing metric (`D-20`) all assume the Policy API is called on every deploy. If nobody wires it in, FreezeHub is a shared calendar with a good audit trail, and no pricing change fixes that.

**The metric already exists and does not need building.** `FZ-113` put a **Pipelines integrated** tile on the dashboard, and `FZ-062` registers `freezehub.policy.evaluations`. Signups are not the number. Organizations whose pipelines are still calling after a month is the number.

What a company will *pay* is a second question, answered with different people — see §4.

## 2. The critical path

```text
FZ-135  decide the region        ← blocked on the operator
   ↓
FZ-046  real Cognito provider    ← nobody can sign in until this exists
   ↓
FZ-122  measure · FZ-123  apply
   ↓
GitHub organization → FZ-099  publish the connector image
   ↓
provision design partners with scripts/provision-organization.sh
```

**`FZ-046` is the hard blocker, and it is easy to miss.** `FZ-086` is DONE, so provisioning works — but its own entry says that outside the `local` profile no Cognito user is created and no invitation is sent, *so the customer cannot sign in*. A deployment made today would be a running system nobody outside the developer's laptop can enter.

**`FZ-135` sits in front of it**, and what it is blocked on is not technical: it is "a commercial judgement about who the product is sold to". That is the same question §4 answers, which makes §4 the thing that unblocks the critical path.

**`FZ-099` is on this path and is blocked on something free.** `OI-13` records that every guideline in `connectors/README.md` names an image that does not exist, so a design partner today has to hand-roll the integration from `examples/`. Asking someone to hand-roll the thing being validated measures the wrong thing.

Nothing about billing, signup or a company appears on this path.

## 3. Deliberately not built yet

| Not doing | Until |
|---|---|
| `FZ-082` self-serve signup | there are more customers than a script can onboard. `D-23` made provisioning a script on purpose and `FZ-086` built it; at this stage manual onboarding is a **feature**, because it forces a conversation with every user |
| A company | somebody wants to pay |
| Stripe, or any payment rail | same trigger — and `OI-31` has to be answered first |
| SOC 2, penetration test | a customer asks |
| A second instance, Multi-AZ, a second region | `FZ-123`'s ladder already prices these and names the trigger for each |

The first five customers can be invoiced by bank transfer or charged nothing. Neither needs a line of code.

## 4. Local or foreign: they answer different questions

The two markets are not interchangeable, and the useful move is to stop treating them as one decision.

**Colombian companies validate the product.** Same timezone, same language, and the founder can be in the room. Fastest possible feedback on §1. But $99/month is roughly 400,000 COP, which is materially expensive for a Colombian SME — so a refusal here is evidence about price, not about value, and must not be read as the latter.

**Foreign companies validate the price.** $99 is immaterial to a US engineering organization, so a "no" is a real "no" about the product. The cost is that they ask for a Data Processing Agreement and a security questionnaire, and `OI-20`'s residency question arrives with the first EU prospect.

**Recommended: validate the product locally, test the price abroad, and do not conclude anything about pricing from a Colombian sample.**

This is the input `FZ-135` is waiting on. A product sold mainly into the EU wants an EU region; one sold locally or into the US does not.

## 5. The account setup worth doing now

Two, both free, both on or adjacent to the critical path. Everything else waits.

- **A GitHub organization.** Unblocks `FZ-099` (`OI-13`), which is on the path.
- **A separate AWS account for production** (`OI-32`). The reason is narrow and is the same shape as `FZ-135`'s: **a Cognito user pool cannot be moved between accounts**, and `FZ-046` is about to create one. Before that story it costs nothing; after the first real user it is a forced password reset for every customer.

Both are worth doing *because of what they unblock or foreclose*, not because a real company would have them. That distinction is what keeps this list at two items.

## 6. What it costs

| | |
|---|---|
| Infrastructure, always-on | ~$26–40/month (`FZ-122`, `FZ-123`) |
| Domain | ~$15/year |
| Company, payment processing, compliance | **$0 at this stage** |
| **A year of validation** | **~$400–500** |

The expensive resource is the time spent on `FZ-046`, not the infrastructure. That ratio is the whole argument for not building the commercial machinery yet.

## 7. Exit criteria

Validation is over — and the commercial machinery becomes worth building — when:

- five organizations have been provisioned;
- at least three have a pipeline that has called the Policy API for thirty consecutive days;
- at least one has said yes to paying.

**Then, and only then:** the legal entity, the payment rail, and `FZ-082`. By that point the first two decisions are informed by knowing whether the customer is Colombian or foreign, which is not knowable today.

**If five partners are onboarded and none wires a pipeline, the problem is not pricing and not the plan.** It is §1, and the answer is to change the product or stop — which is exactly what this stage exists to find out cheaply.
