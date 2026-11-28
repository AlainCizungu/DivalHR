# L2-11: monitoring that does not depend on the host being healthy. Alarms notify an SNS topic whose
# e-mail subscription (the monitored Dival operations address) the operator must confirm before
# acceptance. No metric dimension carries a tenant, employee, e-mail, partner choice, token, code or
# free text.

locals {
  namespace = "Divalhr/Production"
}

resource "aws_sns_topic" "alarms" {
  name              = "${var.name_prefix}-alarms"
  kms_master_key_id = aws_kms_key.main.arn
}

resource "aws_sns_topic_subscription" "operations" {
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# ---- edge -------------------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${var.name_prefix}-alb-5xx"
  alarm_description   = "Application or load balancer 5xx responses"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 2
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "unhealthy_host" {
  alarm_name          = "${var.name_prefix}-unhealthy-host"
  alarm_description   = "The load balancer sees no healthy application host"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
    TargetGroup  = aws_lb_target_group.host.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "waf_block_rate" {
  alarm_name          = "${var.name_prefix}-waf-block-rate"
  alarm_description   = "An unusual number of requests blocked at the edge"
  namespace           = "AWS/WAFV2"
  metric_name         = "BlockedRequests"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 500
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]

  dimensions = {
    WebACL = aws_wafv2_web_acl.main.name
    Region = var.region
    Rule   = "ALL"
  }
}

# ---- host (AWS metrics) -----------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "status_check" {
  alarm_name          = "${var.name_prefix}-host-status-check"
  alarm_description   = "Instance or system status check failed"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    InstanceId = aws_instance.host.id
  }
}

resource "aws_cloudwatch_metric_alarm" "cpu" {
  alarm_name          = "${var.name_prefix}-host-cpu"
  alarm_description   = "Sustained high CPU"
  namespace           = "AWS/EC2"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]

  dimensions = {
    InstanceId = aws_instance.host.id
  }
}

# ---- host (published by ops/production/metrics.sh through the instance role) --------------------
# The heartbeat treats missing data as breaching, so a silent host raises an alarm by itself.
locals {
  host_metric_alarms = {
    "heartbeat"            = { metric = "HostHeartbeat", statistic = "Minimum", comparison = "LessThanThreshold", threshold = 1, periods = 3, missing = "breaching", description = "The host stopped reporting" }
    "disk-used-percent"    = { metric = "DiskUsedPercent", statistic = "Maximum", comparison = "GreaterThanThreshold", threshold = 80, periods = 1, missing = "breaching", description = "The data or root volume is more than 80% full" }
    "memory-used-percent"  = { metric = "MemoryUsedPercent", statistic = "Maximum", comparison = "GreaterThanThreshold", threshold = 90, periods = 3, missing = "breaching", description = "Sustained high memory use" }
    "unhealthy-containers" = { metric = "UnhealthyContainers", statistic = "Maximum", comparison = "GreaterThanThreshold", threshold = 0, periods = 3, missing = "breaching", description = "A container is not healthy" }
    "backup-age-hours"     = { metric = "BackupAgeHours", statistic = "Maximum", comparison = "GreaterThanThreshold", threshold = 26, periods = 1, missing = "breaching", description = "The newest verified off-host backup is older than 26 hours" }
    "restore-drill-age"    = { metric = "RestoreDrillAgeDays", statistic = "Maximum", comparison = "GreaterThanThreshold", threshold = 100, periods = 1, missing = "breaching", description = "The last passing restore drill is older than about a quarter" }
    "deploy-failed"        = { metric = "DeployFailed", statistic = "Maximum", comparison = "GreaterThanThreshold", threshold = 0, periods = 1, missing = "notBreaching", description = "A deployment failed or was rolled back" }
    "watchdog-restarts"    = { metric = "WatchdogRestarts", statistic = "Sum", comparison = "GreaterThanThreshold", threshold = 2, periods = 1, missing = "notBreaching", description = "The watchdog restarted containers repeatedly" }
    "identity-failures"    = { metric = "IdentityFailures", statistic = "Sum", comparison = "GreaterThanThreshold", threshold = 20, periods = 1, missing = "notBreaching", description = "Sign-in or token failures above the usual level" }
    "worker-failures"      = { metric = "WorkerFailures", statistic = "Sum", comparison = "GreaterThanThreshold", threshold = 0, periods = 1, missing = "notBreaching", description = "Queue or background worker failures" }
    "application-5xx"      = { metric = "Application5xx", statistic = "Sum", comparison = "GreaterThanThreshold", threshold = 10, periods = 2, missing = "notBreaching", description = "Core API 5xx responses" }
  }
}

resource "aws_cloudwatch_metric_alarm" "host_metric" {
  for_each            = local.host_metric_alarms
  alarm_name          = "${var.name_prefix}-${each.key}"
  alarm_description   = each.value.description
  namespace           = local.namespace
  metric_name         = each.value.metric
  statistic           = each.value.statistic
  period              = 300
  evaluation_periods  = each.value.periods
  threshold           = each.value.threshold
  comparison_operator = each.value.comparison
  treat_missing_data  = each.value.missing
  alarm_actions       = [aws_sns_topic.alarms.arn]
}

# ---- external availability and certificate expiry ---------------------------------------------
resource "aws_sns_topic" "alarms_us_east_1" {
  provider          = aws.us_east_1
  name              = "${var.name_prefix}-alarms-edge"
  kms_master_key_id = "alias/aws/sns"
}

resource "aws_sns_topic_subscription" "operations_us_east_1" {
  provider  = aws.us_east_1
  topic_arn = aws_sns_topic.alarms_us_east_1.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

resource "aws_route53_health_check" "app" {
  fqdn              = var.domain_app
  type              = "HTTPS"
  port              = 443
  resource_path     = "/healthz"
  request_interval  = 30
  failure_threshold = 3
  enable_sni        = true
  measure_latency   = false

  tags = { Name = "${var.name_prefix}-app-https" }
}

resource "aws_cloudwatch_metric_alarm" "external_https" {
  provider            = aws.us_east_1
  alarm_name          = "${var.name_prefix}-external-https"
  alarm_description   = "The application does not answer over HTTPS from the internet"
  namespace           = "AWS/Route53"
  metric_name         = "HealthCheckStatus"
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alarms_us_east_1.arn]
  ok_actions          = [aws_sns_topic.alarms_us_east_1.arn]

  dimensions = {
    HealthCheckId = aws_route53_health_check.app.id
  }
}

resource "aws_cloudwatch_metric_alarm" "certificate_expiry" {
  alarm_name          = "${var.name_prefix}-certificate-expiry"
  alarm_description   = "The ACM certificate expires in less than 21 days"
  namespace           = "AWS/CertificateManager"
  metric_name         = "DaysToExpiry"
  statistic           = "Minimum"
  period              = 86400
  evaluation_periods  = 1
  threshold           = 21
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]

  dimensions = {
    CertificateArn = var.certificate_arn
  }
}
