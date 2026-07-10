# Business Model: Community Tour Operator Operations

## Classification
- Repository: `cloud-itonami-7912`
- ISIC Rev.5: `7912` — tour operator activities
- Social impact: consumer protection, local jobs, access to travel

## Customer
- independent/community tour operators needing an auditable
  bonding/insolvency-protection platform
- travelers needing verifiable package and reconciliation records
- regulators needing verifiable ATOL/Seller-of-Travel bonding and
  package-organizer-liability compliance records
- programs that cannot accept closed, unauditable tour-packaging
  platforms

## Offer
- bonding and insolvency-protection-scope management
- robotics-assisted itinerary assembly and excursion-logistics
  staging
- package registration, component-booking and reconciliation records
- traveler billing and disclosure records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per operator location
- support retainer with SLA
- itinerary-assembly/excursion-logistics automation integration and
  maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (a package released outside verified
  bonding/insolvency-protection scope, a component booking without a
  completed availability/payment check, an unverified reconciliation
  record) require human sign-off
- packages cannot be released outside verified bonding scope
- reconciliation records require verified evidence
- sensitive traveler and payment data stays outside Git
