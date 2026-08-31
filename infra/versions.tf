terraform {
  required_version = ">= 1.6"

  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.60" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }

  # State holds the database password and the application encryption key, so it must not
  # live on a laptop. Create the bucket and lock table with ./bootstrap first, then fill
  # these in — they cannot be variables, Terraform requires literals here.
  backend "s3" {
    # bucket         = "freezehub-tfstate-<account-id>"
    # key            = "beta/terraform.tfstate"
    # region         = "us-east-1"
    # dynamodb_table = "freezehub-tfstate-lock"
    # encrypt        = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "freezehub"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# CloudFront will only accept a certificate from us-east-1, whatever region everything
# else runs in.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "freezehub"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
