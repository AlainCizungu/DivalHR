# LAUNCH-002 (Issue #230, ADR 0048): the dedicated production stack of the controlled pilot.
# NOTHING here is applied by the pull request that adds it. Provisioning needs a separate, explicit
# founder approval (docs/OPS-PRODUCTION.md section 3). Never reuse hr-dev resources, state or names.
terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "divalhr"
      Environment = "production"
      Stage       = "controlled-pilot"
      ManagedBy   = "terraform"
      Story       = "LAUNCH-002"
    }
  }
}

# Route 53 health-check metrics exist only in us-east-1; the external availability alarm lives there.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "divalhr"
      Environment = "production"
      Stage       = "controlled-pilot"
      ManagedBy   = "terraform"
      Story       = "LAUNCH-002"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
