# L2-2, L2-3, L2-1: the only public entry. TLS ends here (ACM) and is re-encrypted to Caddy on the
# host. ALB access logs stay OFF on purpose: they record the full request URL, and OIDC callbacks
# carry the authorization code and state in the query string (SEC-001).

resource "aws_lb" "main" {
  name                       = "${var.name_prefix}-alb"
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = aws_subnet.public[*].id
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
  enable_deletion_protection = true
  desync_mitigation_mode     = "strictest"
  idle_timeout               = 60

  access_logs {
    bucket  = local.evidence_bucket
    enabled = false
  }
}

resource "aws_lb_target_group" "host" {
  name        = "${var.name_prefix}-host"
  vpc_id      = aws_vpc.main.id
  target_type = "instance"
  protocol    = "HTTPS"
  port        = 8443

  health_check {
    protocol            = "HTTPS"
    path                = "/healthz"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_target_group_attachment" "host" {
  target_group_arn = aws_lb_target_group.host.arn
  target_id        = aws_instance.host.id
  port             = 8443
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  # Any other host name: a guarded 404, never content.
  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "404"
      status_code  = "404"
    }
  }
}

# www redirects to the one canonical commercial origin.
resource "aws_lb_listener_rule" "www_redirect" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10

  action {
    type = "redirect"

    redirect {
      host        = var.domain_commercial
      port        = "443"
      protocol    = "HTTPS"
      path        = "/#{path}"
      query       = "#{query}"
      status_code = "HTTP_301"
    }
  }

  condition {
    host_header {
      values = [var.domain_www]
    }
  }
}

resource "aws_lb_listener_rule" "sites" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 20

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.host.arn
  }

  condition {
    host_header {
      values = [var.domain_commercial, var.domain_app]
    }
  }
}

# Port 80 exists only to redirect to 443 immediately.
resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# DNS is a separately approved step: records are created only when manage_dns is true.
resource "aws_route53_record" "site" {
  for_each = var.manage_dns ? toset([var.domain_commercial, var.domain_www, var.domain_app]) : toset([])
  zone_id  = var.hosted_zone_id
  name     = each.value
  type     = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}
