variable "environment" {
  description = "Environment name, used in every resource name. One workspace per environment."
  type        = string
  default     = "beta"
}

# No default, deliberately (FZ-135, OI-20). This is the one variable that cannot be changed
# after the first apply without moving customer data *and* re-creating every identity: a
# Cognito user pool is region-bound and cannot be migrated, and the `sub` it issues is
# stored in `users.external_subject`. `us-east-1` sat here as a default and was therefore
# never chosen by anybody. It must now be stated, exactly like `domain_name`.
variable "region" {
  description = "AWS region for everything except the CloudFront certificate, which AWS requires from us-east-1. Must be stated: changing it after the first apply is a data migration, not a variable."
  type        = string
}

variable "domain_name" {
  description = "Apex or subdomain the product is served from, e.g. freezehub.example.com. The API is served from api.<domain_name>."
  type        = string
}

variable "hosted_zone_id" {
  description = "Route 53 hosted zone that already delegates domain_name. Certificate validation and the DNS records both need it."
  type        = string
}

variable "db_instance_class" {
  description = "RDS instance class. db.t4g.micro is the smallest that runs PostgreSQL 16 and is adequate for beta load."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "Gigabytes. Storage autoscaling raises it as needed; this is the floor."
  type        = number
  default     = 20
}

variable "backend_image_tag" {
  description = "Image tag in the ECR repository to run. Set by CI (FZ-064); never 'latest', so a rollback is a tag change."
  type        = string
  default     = "bootstrap"
}

variable "backend_desired_count" {
  description = "Number of Fargate tasks. Two so a deployment or an AZ failure does not mean an outage."
  type        = number
  default     = 2
}

variable "backend_cpu" {
  description = "Fargate CPU units. 512 = 0.5 vCPU."
  type        = number
  default     = 512
}

variable "backend_memory" {
  description = "Fargate memory in MiB. The JVM needs headroom above its heap."
  type        = number
  default     = 1024
}

variable "github_repository" {
  description = "owner/name of the repository allowed to deploy. The OIDC trust policy is scoped to it and to the master branch, so getting this wrong is the difference between only this repository deploying and anyone's doing so."
  type        = string
  default     = "acme/freezehub"
}
