# ADR-018: AWS deployment: ECS Fargate in Mumbai, temporary environments, Terraform

- **Status:** Accepted (sizes and managed-service versions settled at V7)
- **Date:** 2026-10-02
- **Related:** [HLD](../architecture.md) §16; requirements Q10, NFR-12, NFR-16; [ADR-001](ADR-001-architecture-style.md), [ADR-003](ADR-003-postgresql-postgis.md), [ADR-004](ADR-004-live-location-index.md), [ADR-007](ADR-007-message-broker.md)

## Context

- Phase 1 chose AWS ap-south-1 (Mumbai), temporary environments under a budget alarm, and ECS Fargate unless Kubernetes experience becomes a goal (Q10). The budget cap and the EKS question are deferred to V7.
- One image runs as four roles (ADR-001). `realtime` holds long-lived WebSocket connections and must drain on deploy.
- The Payment Orchestrator already deploys to ECS Fargate in Mumbai with Terraform.

## Problem

What does the cloud deployment look like, and how is it kept affordable?

## Options considered

| Option | Pros | Cons |
|---|---|---|
| **A. ECS Fargate, one service per role** | No cluster to manage; same as the Payment Orchestrator; per-role scaling | Less portable than Kubernetes; slower task start |
| B. EKS | Kubernetes skills; rich autoscaling | ~$73/month for the control plane alone; more operations work |
| C. Cloud Run (GCP) | Simple | Different cloud from the rest of the portfolio |

## Decision

1. **Compute:** ECS Fargate, one service per role from the same image. `api` and `realtime` sit behind an Application Load Balancer (HTTPS and WebSocket) with AWS WAF in front.
2. **Data:**
   - RDS for PostgreSQL 18 with PostGIS, Multi-AZ;
   - ElastiCache for Valkey in cluster mode, one shard with a replica to start;
   - Amazon MSK from V3;
   - S3 for trip-route archives and exports.
3. **Network:** a VPC across 2–3 availability zones, with services and data stores in private subnets.
4. **Security:** Secrets Manager and KMS. GitHub Actions deploys with OIDC federation, so no long-lived AWS keys exist.
5. **Deploys:**
   - ECS rolling deploys with health checks;
   - for `realtime`, a load-balancer deregistration delay matching the ~30 s client drain (ADR-006);
   - expand-then-contract database migrations.
6. **Autoscaling** (V7):

   | Role | Scales on |
   |---|---|
   | `realtime` | Connections per task |
   | `api` | CPU and request rate |
   | `dispatch` | Due search tasks |
   | `worker` | Outbox age and consumer lag |
7. **Cost:** environments exist only for a test or demo; `terraform apply`, test, `terraform destroy`; AWS Budgets alarms on a cap chosen at V7.
8. **Infrastructure as code:** Terraform with remote state, modules per layer, and `terraform test` checks, following the Payment Orchestrator.

## Trade-offs

- Always-on, this stack costs several hundred US dollars a month; temporary environments keep it to the hours actually used.
- Choosing ECS means no Kubernetes experience from this project unless EKS is chosen at V7.

## Consequences

- The local stack (Compose) and the cloud stack run the same image and the same roles; only endpoints and secrets differ.
- Before V7: confirm RDS support for PostgreSQL 18 with PostGIS 3.6 (fallback: 17, ADR-003), and the MSK version.

## Revisit when

- V7 decisions on budget and EKS.
- WebSocket connection counts call for a dedicated gateway service or a different load-balancing setup (V5).
