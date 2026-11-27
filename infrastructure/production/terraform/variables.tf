# Operator inputs. Values live in an untracked terraform.tfvars or in the operator's evidence, never
# in Git: account identifiers, certificate ARNs, zone identifiers, provider address ranges.

variable "region" {
  description = "The single AWS region of the controlled pilot. Required: choosing it is an operator decision (stop condition of Issue #230)."
  type        = string
}

variable "name_prefix" {
  description = "Prefix of every resource. Must keep the production name apart from hr-dev."
  type        = string
  default     = "divalhr-prod"

  validation {
    condition     = can(regex("^divalhr-prod[a-z0-9-]*$", var.name_prefix)) && !can(regex("test|dev", var.name_prefix))
    error_message = "name_prefix must start with divalhr-prod and must not contain 'test' or 'dev'."
  }
}

variable "vpc_cidr" {
  description = "A dedicated VPC range; never the hr-dev or default VPC."
  type        = string
  default     = "10.80.0.0/16"
}

variable "availability_zones" {
  description = "Two zones: the load balancer needs two public subnets; the single host uses the first."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "Give exactly two availability zones."
  }
}

variable "ami_id" {
  description = "A baked, hardened host image with Docker, Compose, the SSM agent and the CloudWatch agent (the host has no internet to install them)."
  type        = string
}

variable "instance_type" {
  description = "Host size for the controlled pilot; the measured headroom is recorded in the capacity baseline."
  type        = string
  default     = "m6i.large"
}

variable "root_volume_gb" {
  type    = number
  default = 40
}

variable "data_volume_gb" {
  description = "Encrypted application data volume (PostgreSQL, Caddy state, releases, secrets, local backups)."
  type        = number
  default     = 100
}

variable "domain_commercial" {
  type    = string
  default = "divalhr.com"
}

variable "domain_www" {
  type    = string
  default = "www.divalhr.com"
}

variable "domain_app" {
  type    = string
  default = "app.divalhr.com"
}

variable "certificate_arn" {
  description = "ACM certificate in this region covering the three names. Requested and validated as a separately approved step."
  type        = string
}

variable "manage_dns" {
  description = "false: DNS is changed by hand under a separate approval. true: Terraform creates the alias records."
  type        = bool
  default     = false
}

variable "hosted_zone_id" {
  description = "Hosted zone of divalhr.com; only used when manage_dns is true."
  type        = string
  default     = ""
}

variable "alarm_email" {
  description = "Monitored Dival operations address that receives alarms (the approved public contact)."
  type        = string
  default     = "contact@dival.ai"
}

variable "email_submission_port" {
  description = "SMTP submission port of the Amazon SES interface endpoint (25, 465, 587, 2465 or 2587)."
  type        = number
  default     = 587

  validation {
    condition     = contains([25, 465, 587, 2465, 2587], var.email_submission_port)
    error_message = "The SES SMTP endpoint accepts only ports 25, 465, 587, 2465 and 2587."
  }
}

variable "emergency_allow_cidrs" {
  description = "Time-boxed operator addresses exempt from WAF rate rules during an incident. Empty by default; never a permanent bypass."
  type        = list(string)
  default     = []
}

variable "public_rate_limit" {
  description = "Requests per client address per minute on /api/v1/public/."
  type        = number
  default     = 100
}

variable "abuse_rate_limit" {
  description = "Requests per client address per five minutes, any path."
  type        = number
  default     = 2000
}

variable "max_body_bytes" {
  description = "Largest request body (Content-Length) the edge accepts; aligned with the application's upload limits."
  type        = number
  default     = 10485760
}

variable "backup_noncurrent_days" {
  description = "Days an overwritten or deleted backup object version is kept. An engineering setting pending legal review, not a retention promise."
  type        = number
  default     = 35
}

variable "evidence_retention_days" {
  description = "Object Lock (governance) retention of release evidence. An engineering setting pending legal review."
  type        = number
  default     = 365
}

variable "snapshot_retention_count" {
  type    = number
  default = 14
}

variable "log_retention_days" {
  description = "CloudWatch log retention. An engineering setting pending legal review, not a legal retention claim."
  type        = number
  default     = 90
}

variable "services" {
  description = "Application images built once from an exact commit (ops/production/build-push.sh) and pulled by the host."
  type        = list(string)
  default     = ["web", "core-api", "ai-service", "keycloak"]
}
