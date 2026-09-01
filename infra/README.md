# infra

Terraform for the AWS deployment target (`FZ-063`). One environment per workspace; `beta` is the default.

## What it creates

```text
                    Route 53
                       │
        ┌──────────────┴───────────────┐
        │                              │
   CloudFront                    ALB (HTTPS)
        │                              │
   S3 (private)                  ECS Fargate ×2        Cognito
   frontend build                 backend image        user pool
                                        │
                                  RDS PostgreSQL
                                   (private subnets)
```

Plus: ECR for backend images, Secrets Manager for the database password and the
application encryption key, and CloudWatch logs.

## Before the first apply

You need, and Terraform will not create for you:

1. **A domain and a Route 53 hosted zone that already delegates it.** Both certificates
   are DNS-validated through that zone, so an apply hangs without it.
2. **An IAM user or role for Terraform — not account root.** Root access keys cannot be
   scoped, cannot be limited, and cannot be revoked without disrupting everything else.
3. **A verified SES identity**, if email notifications are wanted. The task role can send;
   SES still has to be out of the sandbox to send anywhere.

## Applying

```bash
# once per account: the bucket that holds state, which contains secrets
cd bootstrap && terraform init && terraform apply
# note the bucket name it prints, then uncomment and fill in the backend block in
# ../versions.tf

cd .. && cp terraform.tfvars.example terraform.tfvars   # set domain_name, hosted_zone_id
terraform init
terraform plan     # read-only; read it before applying
terraform apply
```

The first apply takes roughly 15–25 minutes, most of it RDS and the CloudFront
distribution. Certificate validation blocks until the DNS records propagate.

## Deploying the application

`FZ-064` automates this in `.github/workflows/deploy.yml`, which assumes a role by OIDC rather than any stored key. After the first apply, set these as repository variables in GitHub — every one is a `terraform output`:

```text
AWS_DEPLOY_ROLE_ARN        github_deploy_role_arn
AWS_REGION                 (your region)
ECR_REPOSITORY             (the repository name from ecr_repository_url)
ECS_CLUSTER                ecs_cluster_name
ECS_SERVICE                ecs_service_name
ECS_TASK_FAMILY            ecs_task_family
FRONTEND_BUCKET            frontend_bucket
CLOUDFRONT_DISTRIBUTION_ID cloudfront_distribution_id
```

Set `github_repository` in `terraform.tfvars` before applying: the OIDC trust policy is scoped to it, and getting it wrong is the difference between only this repository being able to deploy and anyone's being able to.

By hand:

```bash
# backend — the image must be linux/arm64, which is what the task definition runs
aws ecr get-login-password | docker login --username AWS --password-stdin "$(terraform output -raw ecr_repository_url)"
docker buildx build --platform linux/arm64 -t "$(terraform output -raw ecr_repository_url):$(git rev-parse --short HEAD)" backend/   # backend/Dockerfile
docker push "$(terraform output -raw ecr_repository_url):$(git rev-parse --short HEAD)"
# then register a revision naming that image and update the service. NOT
# `terraform apply`: the service ignores task_definition changes so CI and Terraform
# do not fight over the image tag.

# frontend
(cd frontend && npm run build)
aws s3 sync frontend/dist "s3://$(terraform output -raw frontend_bucket)" --delete
aws cloudfront create-invalidation --distribution-id "$(terraform output -raw cloudfront_distribution_id)" --paths '/*'
```

Image tags are **immutable** and never `latest`, so a rollback is a tag change rather than
a rebuild and hope.

## Things worth knowing before you rely on this

- **`FREEZEHUB_SECRETS_ENCRYPTION_KEY` is generated once and never rotated.** Losing or
  replacing it makes every stored channel credential and webhook signing secret
  permanently unreadable (`FZ-049`, decision `D-3`). Both it and its Secrets Manager entry
  are marked `prevent_destroy`; do not work around that.
- **`FZ-046` is not built yet.** The backend has no real `IdentityProvider`, so it will
  refuse to start outside the `local` profile — meaning this infrastructure can be created
  but the service will not come up until that ships. This is deliberate sequencing
  (decision `D-4`), not an oversight.
- **The state bucket holds secrets.** Treat access to it as access to the database.
- **Single NAT gateway and single-AZ RDS**, both deliberate cost choices for beta. Neither
  is appropriate behind an availability commitment.
- **Nothing here has been applied.** The configuration validates and is formatted; it has
  never been run against an AWS account, so plan-time and apply-time errors are still
  possible. Read the first plan carefully.

## Rough monthly cost

Order of magnitude, us-east-1, beta scale, before data transfer:

| | |
|---|---|
| ECS Fargate, 2 × 0.5 vCPU / 1 GB, ARM | ~$30 |
| RDS `db.t4g.micro`, single-AZ, 20 GB | ~$15 |
| NAT gateway | ~$32 + data |
| ALB | ~$16 |
| CloudFront, S3, Secrets Manager, ECR | ~$5 |
| Cognito Lite | free to 10,000 MAU |
| **Total** | **~$100/month** |

The NAT gateway is the one that looks disproportionate at this scale. It exists so the
application and database sit in private subnets; putting tasks in public subnets with
public IPs would remove it and weaken that boundary.
