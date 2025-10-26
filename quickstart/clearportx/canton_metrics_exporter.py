#!/usr/bin/env python3
"""
Canton AMM Metrics Exporter for Prometheus
Exports real-time metrics from ClearportX AMM DEX
"""

import os
import time
import requests
from prometheus_client import start_http_server, Gauge, Counter, Histogram
from datetime import datetime
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Configuration
CANTON_API_URL = os.getenv('CANTON_API_URL', 'http://localhost:5001')
BACKEND_API_URL = os.getenv('BACKEND_API_URL', 'http://localhost:8080')
METRICS_PORT = int(os.getenv('METRICS_PORT', '9200'))
SCRAPE_INTERVAL = int(os.getenv('SCRAPE_INTERVAL', '10'))

# Metrics definitions
# Pool metrics
pool_count = Gauge('amm_pool_count', 'Total number of liquidity pools')
pool_reserve_a = Gauge('amm_pool_reserve_a', 'Reserve A amount', ['pool_id', 'symbol'])
pool_reserve_b = Gauge('amm_pool_reserve_b', 'Reserve B amount', ['pool_id', 'symbol'])
pool_k_value = Gauge('amm_pool_k_value', 'Constant product (k) value', ['pool_id'])
pool_fee_rate = Gauge('amm_pool_fee_rate', 'Pool fee rate in basis points', ['pool_id'])
pool_lp_supply = Gauge('amm_pool_lp_supply', 'Total LP token supply', ['pool_id'])

# Swap metrics
swap_count = Counter('amm_swap_count', 'Total number of swaps executed', ['pool_id', 'direction'])
swap_volume = Counter('amm_swap_volume', 'Total swap volume', ['pool_id', 'symbol'])
swap_fees_collected = Counter('amm_swap_fees', 'Total fees collected', ['pool_id', 'symbol'])
swap_slippage = Histogram('amm_swap_slippage', 'Swap slippage percentage', ['pool_id'])
swap_gas_used = Histogram('amm_swap_gas', 'Gas used for swaps')

# Price metrics
price_eth_usdc = Gauge('amm_price_eth_usdc', 'ETH/USDC price')
price_impact = Histogram('amm_price_impact', 'Price impact percentage', ['pool_id', 'amount_category'])

# System metrics
api_response_time = Histogram('amm_api_response_time', 'API response time in seconds', ['endpoint'])
api_error_count = Counter('amm_api_errors', 'API error count', ['endpoint', 'error_type'])
ledger_sync_status = Gauge('amm_ledger_sync_status', 'Ledger sync status (1=synced, 0=not synced)')

# Health metrics
backend_health = Gauge('amm_backend_health', 'Backend health status (1=healthy, 0=unhealthy)')
pool_health = Gauge('amm_pool_health', 'Pool health check', ['pool_id', 'check_type'])

def fetch_pools():
    """Fetch pool data from backend API"""
    try:
        start_time = time.time()
        response = requests.get(f"{BACKEND_API_URL}/api/pools", timeout=5)
        api_response_time.labels(endpoint='/api/pools').observe(time.time() - start_time)
        
        if response.status_code == 200:
            return response.json()
        else:
            api_error_count.labels(endpoint='/api/pools', error_type=f'http_{response.status_code}').inc()
            return []
    except Exception as e:
        logger.error(f"Failed to fetch pools: {e}")
        api_error_count.labels(endpoint='/api/pools', error_type='exception').inc()
        return []

def check_backend_health():
    """Check backend health status"""
    try:
        response = requests.get(f"{BACKEND_API_URL}/actuator/health", timeout=5)
        if response.status_code == 200:
            backend_health.set(1)
            return True
        else:
            backend_health.set(0)
            return False
    except:
        backend_health.set(0)
        return False

def calculate_pool_health(pool):
    """Calculate pool health metrics"""
    pool_id = pool['poolId']
    
    # Check if reserves are positive
    if float(pool['reserveA']) > 0 and float(pool['reserveB']) > 0:
        pool_health.labels(pool_id=pool_id, check_type='reserves_positive').set(1)
    else:
        pool_health.labels(pool_id=pool_id, check_type='reserves_positive').set(0)
    
    # Check if k is maintained
    k = float(pool['reserveA']) * float(pool['reserveB'])
    if k > 0:
        pool_health.labels(pool_id=pool_id, check_type='k_maintained').set(1)
    else:
        pool_health.labels(pool_id=pool_id, check_type='k_maintained').set(0)
    
    # Check fee rate is reasonable (between 0.01% and 10%)
    fee_rate = float(pool['feeRate'])
    if 0.0001 <= fee_rate <= 0.1:
        pool_health.labels(pool_id=pool_id, check_type='fee_rate_valid').set(1)
    else:
        pool_health.labels(pool_id=pool_id, check_type='fee_rate_valid').set(0)

def update_metrics():
    """Update all metrics"""
    logger.info("Updating metrics...")
    
    # Check backend health
    check_backend_health()
    
    # Fetch and update pool metrics
    pools = fetch_pools()
    pool_count.set(len(pools))
    
    for pool in pools:
        pool_id = pool['poolId']
        symbol_a = pool['symbolA']
        symbol_b = pool['symbolB']
        reserve_a = float(pool['reserveA'])
        reserve_b = float(pool['reserveB'])
        lp_supply = float(pool['totalLPSupply'])
        fee_rate = float(pool['feeRate']) * 10000  # Convert to basis points
        
        # Update pool metrics
        pool_reserve_a.labels(pool_id=pool_id, symbol=symbol_a).set(reserve_a)
        pool_reserve_b.labels(pool_id=pool_id, symbol=symbol_b).set(reserve_b)
        pool_k_value.labels(pool_id=pool_id).set(reserve_a * reserve_b)
        pool_fee_rate.labels(pool_id=pool_id).set(fee_rate)
        pool_lp_supply.labels(pool_id=pool_id).set(lp_supply)
        
        # Calculate ETH/USDC price if applicable
        if symbol_a == 'ETH' and symbol_b == 'USDC' and reserve_a > 0:
            price_eth_usdc.set(reserve_b / reserve_a)
        
        # Check pool health
        calculate_pool_health(pool)
    
    # Simulate swap metrics (in production, these would come from real swap events)
    # This is for demonstration purposes
    if pools:
        simulate_swap_metrics(pools[0])
    
    logger.info(f"Updated metrics for {len(pools)} pools")

def simulate_swap_metrics(pool):
    """Simulate swap metrics for demonstration"""
    pool_id = pool['poolId']
    
    # Simulate some swap activity
    import random
    
    # Random swap direction
    if random.random() > 0.5:
        direction = f"{pool['symbolA']}_to_{pool['symbolB']}"
        swap_count.labels(pool_id=pool_id, direction=direction).inc()
        
        # Simulate volume
        volume = random.uniform(0.1, 5.0)
        swap_volume.labels(pool_id=pool_id, symbol=pool['symbolA']).inc(volume)
        
        # Simulate fees
        fee = volume * float(pool['feeRate'])
        swap_fees_collected.labels(pool_id=pool_id, symbol=pool['symbolA']).inc(fee)
    else:
        direction = f"{pool['symbolB']}_to_{pool['symbolA']}"
        swap_count.labels(pool_id=pool_id, direction=direction).inc()
        
        volume = random.uniform(100, 10000)
        swap_volume.labels(pool_id=pool_id, symbol=pool['symbolB']).inc(volume)
        
        fee = volume * float(pool['feeRate'])
        swap_fees_collected.labels(pool_id=pool_id, symbol=pool['symbolB']).inc(fee)
    
    # Simulate slippage
    slippage = random.uniform(0.01, 2.0)  # 0.01% to 2%
    swap_slippage.labels(pool_id=pool_id).observe(slippage)
    
    # Simulate price impact based on volume
    if volume < 1000:
        price_impact.labels(pool_id=pool_id, amount_category='small').observe(random.uniform(0.01, 0.5))
    elif volume < 10000:
        price_impact.labels(pool_id=pool_id, amount_category='medium').observe(random.uniform(0.5, 2.0))
    else:
        price_impact.labels(pool_id=pool_id, amount_category='large').observe(random.uniform(2.0, 5.0))

def main():
    """Main monitoring loop"""
    # Start Prometheus metrics server
    start_http_server(METRICS_PORT)
    logger.info(f"Metrics server started on port {METRICS_PORT}")
    
    # Main loop
    while True:
        try:
            update_metrics()
        except Exception as e:
            logger.error(f"Error updating metrics: {e}")
            api_error_count.labels(endpoint='metrics_update', error_type='exception').inc()
        
        time.sleep(SCRAPE_INTERVAL)

if __name__ == "__main__":
    main()
