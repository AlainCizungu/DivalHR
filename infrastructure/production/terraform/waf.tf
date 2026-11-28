# L2-4: AWS WAF on the load balancer. Rate rules count the client address the load balancer saw
# (aggregate key IP), never a client-supplied forwarded header. The application limiter stays on.

resource "aws_wafv2_ip_set" "emergency" {
  name               = "${var.name_prefix}-emergency-operator"
  description        = "Time-boxed operator addresses during an incident. Empty by default."
  scope              = "REGIONAL"
  ip_address_version = "IPV4"
  addresses          = var.emergency_allow_cidrs
}

resource "aws_wafv2_web_acl" "main" {
  name        = "${var.name_prefix}-edge"
  description = "DivalHR production edge"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "emergency-operator-allow"
    priority = 0

    action {
      allow {}
    }

    statement {
      ip_set_reference_statement {
        arn = aws_wafv2_ip_set.emergency.arn
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "emergency-operator-allow"
      sampled_requests_enabled   = false
    }
  }

  rule {
    name     = "body-size-limit"
    priority = 5

    action {
      block {}
    }

    statement {
      size_constraint_statement {
        comparison_operator = "GT"
        size                = var.max_body_bytes

        field_to_match {
          single_header {
            name = "content-length"
          }
        }

        text_transformation {
          priority = 0
          type     = "NONE"
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "body-size-limit"
      sampled_requests_enabled   = false
    }
  }

  rule {
    name     = "aws-managed-common"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"

        # The managed body-size rule looks at the first 8 KB only and would refuse legitimate
        # uploads; the explicit size rule above bounds bodies by Content-Length instead.
        rule_action_override {
          name = "SizeRestrictions_BODY"
          action_to_use {
            count {}
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-managed-common"
      sampled_requests_enabled   = false
    }
  }

  rule {
    name     = "aws-managed-known-bad-inputs"
    priority = 20

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-managed-known-bad-inputs"
      sampled_requests_enabled   = false
    }
  }

  rule {
    name     = "public-endpoints-rate-limit"
    priority = 30

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit                 = var.public_rate_limit
        aggregate_key_type    = "IP"
        evaluation_window_sec = 60

        scope_down_statement {
          byte_match_statement {
            search_string         = "/api/v1/public/"
            positional_constraint = "STARTS_WITH"

            field_to_match {
              uri_path {}
            }

            text_transformation {
              priority = 0
              type     = "NONE"
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "public-endpoints-rate-limit"
      sampled_requests_enabled   = false
    }
  }

  rule {
    name     = "abusive-traffic-rate-limit"
    priority = 40

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit                 = var.abuse_rate_limit
        aggregate_key_type    = "IP"
        evaluation_window_sec = 300
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "abusive-traffic-rate-limit"
      sampled_requests_enabled   = false
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "divalhr-production-edge"
    sampled_requests_enabled   = false
  }
}

resource "aws_wafv2_web_acl_association" "alb" {
  resource_arn = aws_lb.main.arn
  web_acl_arn  = aws_wafv2_web_acl.main.arn
}

# WAF log: blocked and counted requests only; secrets and personal values are redacted before the
# log is written. The group name must start with aws-waf-logs-.
resource "aws_cloudwatch_log_group" "waf" {
  name              = "aws-waf-logs-${var.name_prefix}"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.main.arn
}

resource "aws_wafv2_web_acl_logging_configuration" "main" {
  resource_arn            = aws_wafv2_web_acl.main.arn
  log_destination_configs = [aws_cloudwatch_log_group.waf.arn]

  redacted_fields {
    query_string {}
  }

  redacted_fields {
    single_header {
      name = "authorization"
    }
  }

  redacted_fields {
    single_header {
      name = "cookie"
    }
  }

  redacted_fields {
    single_header {
      name = "set-cookie"
    }
  }

  redacted_fields {
    single_header {
      name = "idempotency-key"
    }
  }

  redacted_fields {
    single_header {
      name = "x-hub-signature-256"
    }
  }

  redacted_fields {
    single_header {
      name = "x-divalhr-signature"
    }
  }

  redacted_fields {
    single_header {
      name = "x-divalhr-timestamp"
    }
  }

  redacted_fields {
    single_header {
      name = "x-divalhr-callback-id"
    }
  }

  redacted_fields {
    single_header {
      name = "x-receipt-file-name"
    }
  }

  logging_filter {
    default_behavior = "DROP"

    filter {
      behavior    = "KEEP"
      requirement = "MEETS_ANY"

      condition {
        action_condition {
          action = "BLOCK"
        }
      }

      condition {
        action_condition {
          action = "COUNT"
        }
      }
    }
  }
}
