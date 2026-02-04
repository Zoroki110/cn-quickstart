# ClearportX Metrics Guide

## Overview

ClearportX provides comprehensive Prometheus-compatible metrics for monitoring atomic swap operations, pool liquidity, fee collection, and system health.

## Metrics Endpoints

### Prometheus Metrics
- **Endpoint**: `/api/actuator/prometheus`
- **Format**: Prometheus text format
- **Use**: Scrape with Prometheus for monitoring and alerting

### JSON Metrics
- **Endpoint**: `/api/actuator/metrics/{metric.name}`
- **Format**: JSON
- **Use**: Ad-hoc metric queries and debugging

### Health Check
- **Endpoint**: `/api/health/ledger`
- **Includes**: System status, DAR version, atomic swap availability

## Available Metrics

### Swap Counters

#### `clearportx_swap_prepared_total`
Total number of swaps prepared (PrepareSwap choice executed).

**Type**: Counter
**Tags**: None
**Example**:
```
clearportx_swap_prepared_total 145
```

#### `clearportx_swap_prepared_by_pair`
Swap preparations by specific token pairs.

**Type**: Counter
**Tags**: `input`, `output`
**Example**:
```
clearportx_swap_prepared_by_pair{input="ETH",output="USDC"} 89
clearportx_swap_prepared_by_pair{input="USDC",output="ETH"} 56
```

#### `clearportx_swap_executed_total`
Total number of successfully executed swaps.

**Type**: Counter
**Example**:
```
clearportx_swap_executed_total 142
```

#### `clearportx_swap_executed_by_pair`
Successful swap executions by token pair.

**Type**: Counter
**Tags**: `input`, `output`
**Example**:
```
clearportx_swap_executed_by_pair{input="ETH",output="USDC"} 88
clearportx_swap_executed_by_pair{input="USDC",output="ETH"} 54
```

#### `clearportx_swap_failed_total`
Total number of failed swaps.

**Type**: Counter
**Example**:
```
clearportx_swap_failed_total 3
```

#### `clearportx_swap_failed_by_reason`
Failed swaps categorized by failure reason.

**Type**: Counter
**Tags**: `reason`, `input`, `output`
**Example**:
```
clearportx_swap_failed_by_reason{reason="ResponseStatusException",input="ETH",output="USDC"} 2
clearportx_swap_failed_by_reason{reason="TimeoutException",input="BTC",output="USDC"} 1
```

### Swap Amounts

#### `clearportx_swap_input_amount`
Distribution of swap input amounts.

**Type**: DistributionSummary
**Unit**: tokens
**Percentiles**: p50, p90, p95, p99
**Example**:
```
clearportx_swap_input_amount_count 142
clearportx_swap_input_amount_sum 358.5
clearportx_swap_input_amount_max 10.0
```

#### `clearportx_swap_output_amount`
Distribution of swap output amounts.

**Type**: DistributionSummary
**Unit**: tokens
**Percentiles**: p50, p90, p95, p99
**Example**:
```
clearportx_swap_output_amount_count 142
clearportx_swap_output_amount_sum 695432.8
clearportx_swap_output_amount_max 18500.0
```

### Price Impact

#### `clearportx_swap_price_impact_bps`
Distribution of price impact in basis points (1 bp = 0.01%).

**Type**: DistributionSummary
**Unit**: basis points (bps)
**Percentiles**: p50, p90, p95, p99
**Example**:
```
clearportx_swap_price_impact_bps_count 142
clearportx_swap_price_impact_bps_sum 14250
clearportx_swap_price_impact_bps_max 850
```

**Interpreting Values**:
- `< 50 bps`: Very low impact (< 0.5%)
- `50-200 bps`: Normal range (0.5-2%)
- `200-500 bps`: Moderate impact (2-5%)
- `> 500 bps`: High impact (> 5%)

### Execution Time

#### `clearportx_swap_execution_time`
Time taken to execute atomic swaps (from ExecuteSwap choice to receipt creation).

**Type**: Timer
**Unit**: milliseconds
**Percentiles**: p50, p90, p95, p99
**SLOs**: 100ms, 500ms, 1s, 2s, 5s
**Example**:
```
clearportx_swap_execution_time_seconds_count 142
clearportx_swap_execution_time_seconds_sum 42.5
clearportx_swap_execution_time_seconds_max 1.2
```

### Pool Metrics

#### `clearportx_pool_active_count`
Number of active liquidity pools.

**Type**: Gauge
**Example**:
```
clearportx_pool_active_count 3
```

#### `clearportx_pool_reserve_amount`
Current reserve amounts in pools.

**Type**: Gauge
**Tags**: `pool`, `token`
**Unit**: tokens
**Example**:
```
clearportx_pool_reserve_amount{pool="ETH-USDC",token="ETH"} 105.5
clearportx_pool_reserve_amount{pool="ETH-USDC",token="USDC"} 205432.8
```

#### `clearportx_pool_k_invariant`
Constant product k = reserveA * reserveB for each pool.

**Type**: Gauge
**Tags**: `pool`
**Example**:
```
clearportx_pool_k_invariant{pool="ETH-USDC"} 21673150.4
```

**Note**: K should never decrease between swaps (only increases due to fees).

### Fee Collection

#### `clearportx_fees_lp_collected`
Total fees collected for liquidity providers (75% of swap fees).

**Type**: Counter
**Tags**: `token`
**Unit**: tokens
**Example**:
```
clearportx_fees_lp_collected{token="ETH"} 0.8025
```

#### `clearportx_fees_protocol_collected`
Total protocol fees collected (25% of swap fees).

**Type**: Counter
**Tags**: `token`
**Unit**: tokens
**Example**:
```
clearportx_fees_protocol_collected{token="ETH"} 0.2675
```

### Protection Mechanisms

#### `clearportx_swap_slippage_protection_triggered`
Number of times slippage protection prevented a swap.

**Type**: Counter
**Tags**: `input`, `output`
**Example**:
```
clearportx_swap_slippage_protection_triggered{input="ETH",output="USDC"} 5
```

#### `clearportx_swap_slippage_protection_delta_bps`
How much price impact exceeded the user's limit (in bps) when slippage protection triggered.

**Type**: DistributionSummary
**Unit**: basis points
**Example**:
```
clearportx_swap_slippage_protection_delta_bps_sum 125
clearportx_swap_slippage_protection_delta_bps_count 5
```

#### `clearportx_swap_deadline_expired`
Number of swaps rejected due to deadline expiration.

**Type**: Counter
**Tags**: `input`, `output`
**Example**:
```
clearportx_swap_deadline_expired{input="ETH",output="USDC"} 2
```

#### `clearportx_swap_concurrent_total`
Number of concurrent swap attempts on the same pool.

**Type**: Counter
**Tags**: `pool`
**Example**:
```
clearportx_swap_concurrent_total{pool="00a1b2c3..."} 8
```

## Example Queries

### Prometheus/PromQL

#### Swap Success Rate
```promql
rate(clearportx_swap_executed_total[5m]) /
rate(clearportx_swap_prepared_total[5m]) * 100
```

#### Average Swap Size
```promql
rate(clearportx_swap_input_amount_sum[5m]) /
rate(clearportx_swap_input_amount_count[5m])
```

#### 95th Percentile Execution Time
```promql
histogram_quantile(0.95,
  rate(clearportx_swap_execution_time_seconds_bucket[5m])
)
```

#### Total Fees Collected (All Tokens)
```promql
sum(clearportx_fees_lp_collected) +
sum(clearportx_fees_protocol_collected)
```

#### Pool Utilization (ETH-USDC)
```promql
clearportx_swap_executed_by_pair{input="ETH",output="USDC"} +
clearportx_swap_executed_by_pair{input="USDC",output="ETH"}
```

## Grafana Dashboard

### Recommended Panels

1. **Swap Throughput**
   - Metric: `rate(clearportx_swap_executed_total[5m])`
   - Type: Graph
   - Unit: swaps/sec

2. **Execution Time**
   - Metric: `clearportx_swap_execution_time_seconds`
   - Type: Heatmap
   - Percentiles: p50, p90, p99

3. **Pool Liquidity**
   - Metric: `clearportx_pool_reserve_amount`
   - Type: Graph
   - Group by: pool, token

4. **Price Impact Distribution**
   - Metric: `clearportx_swap_price_impact_bps`
   - Type: Histogram
   - Buckets: 0-50, 50-100, 100-200, 200-500, 500+

5. **Fee Revenue**
   - Metrics: `clearportx_fees_lp_collected`, `clearportx_fees_protocol_collected`
   - Type: Stacked graph
   - Unit: tokens

## Alerting Rules

### Critical Alerts

```yaml
groups:
  - name: clearportx_critical
    rules:
      # High failure rate
      - alert: HighSwapFailureRate
        expr: |
          rate(clearportx_swap_failed_total[5m]) /
          rate(clearportx_swap_prepared_total[5m]) > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High swap failure rate ({{ $value | humanizePercentage }})"

      # Slow execution
      - alert: SlowSwapExecution
        expr: |
          histogram_quantile(0.95,
            rate(clearportx_swap_execution_time_seconds_bucket[5m])
          ) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "95th percentile execution time > 5s"

      # Pool liquidity low
      - alert: LowPoolLiquidity
        expr: |
          clearportx_pool_reserve_amount < 1
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Pool {{ $labels.pool }} {{ $labels.token }} reserve < 1"
```

## Configuration

Metrics are configured in [application-metrics.yml](../../backend/src/main/resources/application-metrics.yml):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    distribution:
      percentiles-histogram:
        clearportx.swap.execution.time: true
      slo:
        clearportx.swap.execution.time: 100,500,1000,2000,5000
```

## Best Practices

1. **Scrape Interval**: 15-30 seconds for production
2. **Retention**: 15+ days for historical analysis
3. **Cardinality**: Monitor tag cardinality (pools × tokens)
4. **Alerting**: Set up alerts for failure rate, execution time, liquidity
5. **Dashboards**: Create role-specific dashboards (traders, LPs, admins)

## Troubleshooting

### High Failure Rate
1. Check `clearportx_swap_failed_by_reason` for error types
2. Review logs for detailed error messages
3. Verify pool liquidity levels

### Slow Execution
1. Check Canton participant health
2. Verify PQS performance
3. Review concurrent swap metrics

### Metric Missing
1. Verify actuator endpoints enabled
2. Check Spring Boot application logs
3. Ensure SwapMetrics bean initialized

---

**Generated**: Phase 3 - Observability & Monitoring
**Version**: 1.0.1
**Contact**: See [README](../README.md) for support
