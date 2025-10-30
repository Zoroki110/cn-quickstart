import { test, expect } from '@playwright/test';

test.describe('🔧 Debug Liquidity Pool Issue', () => {
  
  test('🏊 Fix and test liquidity pools', async ({ page }) => {
    console.log('🔧 DEBUGGING LIQUIDITY POOL ISSUE');
    console.log('=================================');
    
    await page.goto('http://localhost:3001');
    await page.waitForLoadState('networkidle');
    
    // Step 1: Inject proper mock data
    console.log('📝 Injecting comprehensive mock data...');
    
    await page.evaluate(() => {
      const mockTokens = [
        { symbol: 'USDC', name: 'USD Coin', decimals: 6, balance: 10000.50, contractId: 'mock-usdc-001' },
        { symbol: 'ETH', name: 'Ethereum', decimals: 18, balance: 25.75, contractId: 'mock-eth-001' },
        { symbol: 'BTC', name: 'Bitcoin', decimals: 8, balance: 2.5, contractId: 'mock-btc-001' },
        { symbol: 'USDT', name: 'Tether USD', decimals: 6, balance: 8500.25, contractId: 'mock-usdt-001' }
      ];
      
      const mockPools = [
        {
          contractId: 'mock-pool-usdc-eth-001',
          tokenA: { symbol: 'USDC', name: 'USD Coin', decimals: 6 },
          tokenB: { symbol: 'ETH', name: 'Ethereum', decimals: 18 },
          reserveA: 50000.0,
          reserveB: 25.0,
          totalLiquidity: 1118.03,
          feeRate: 0.003,
          apr: 12.5,
          volume24h: 125000,
          userLiquidity: 0,
          userShare: 0
        },
        {
          contractId: 'mock-pool-usdc-btc-001',
          tokenA: { symbol: 'USDC', name: 'USD Coin', decimals: 6 },
          tokenB: { symbol: 'BTC', name: 'Bitcoin', decimals: 8 },
          reserveA: 100000.0,
          reserveB: 2.0,
          totalLiquidity: 447.21,
          feeRate: 0.003,
          apr: 8.2,
          volume24h: 85000,
          userLiquidity: 0,
          userShare: 0
        }
      ];
      
      // Store in localStorage
      localStorage.setItem('canton-amm-mock-tokens', JSON.stringify(mockTokens));
      localStorage.setItem('canton-amm-mock-pools', JSON.stringify(mockPools));
      
      // Configure app store for mock mode
      const appStore = {
        isConnected: false,
        participantId: 'amm_participant',
        currentParty: 'Alice',
        tokens: mockTokens,
        pools: mockPools,
        selectedTokens: { from: null, to: null },
        transactions: [],
        pendingTransactions: [],
        liquidityPositions: [],
        slippage: 0.5,
        deadline: 20,
        isExpertMode: false,
        theme: 'light'
      };
      
      localStorage.setItem('canton-amm-store', JSON.stringify(appStore));
      
      console.log('Mock data injected:', {
        tokens: mockTokens.length,
        pools: mockPools.length
      });
    });
    
    // Reload to apply changes
    await page.reload();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);
    
    console.log('✅ Mock data injected and page reloaded');
    
    // Step 2: Test swap functionality
    console.log('💱 Testing swap with injected data...');
    
    await page.goto('http://localhost:3001/#/swap');
    await page.waitForTimeout(2000);
    
    // Select USDC
    const selectButtons = page.locator('button:has-text("Select")');
    const selectCount = await selectButtons.count();
    console.log(`Found ${selectCount} select buttons`);
    
    if (selectCount >= 2) {
      await selectButtons.first().click();
      await page.waitForTimeout(1000);
      
      // Look for USDC option
      const usdcButton = page.locator('button').filter({ hasText: 'USDC' });
      if (await usdcButton.count() > 0) {
        await usdcButton.first().click();
        await page.waitForTimeout(1000);
        console.log('✅ USDC selected');
      }
      
      // Select ETH
      const selectButtonsAfter = page.locator('button:has-text("Select")');
      if (await selectButtonsAfter.count() > 0) {
        await selectButtonsAfter.last().click();
        await page.waitForTimeout(1000);
        
        const ethButton = page.locator('button').filter({ hasText: 'ETH' });
        if (await ethButton.count() > 0) {
          await ethButton.first().click();
          await page.waitForTimeout(1000);
          console.log('✅ ETH selected');
        }
      }
    }
    
    // Enter amount
    const amountInput = page.locator('input[type="number"]').first();
    await amountInput.fill('100');
    await page.waitForTimeout(2000);
    console.log('✅ Amount entered: 100 USDC');
    
    // Check if quote is calculated
    const outputInput = page.locator('input[type="number"]').last();
    const outputValue = await outputInput.inputValue();
    console.log(`Quote result: ${outputValue} ETH`);
    
    // Check for error messages
    const errorMessages = await page.evaluate(() => {
      const bodyText = document.body.innerText;
      return {
        hasNoPoolError: bodyText.includes('No liquidity pool') || bodyText.includes('no pool'),
        hasInsufficientError: bodyText.includes('Insufficient'),
        hasSelectTokenError: bodyText.includes('Select'),
        bodySnippet: bodyText.substring(bodyText.indexOf('Swap'), bodyText.indexOf('Swap') + 300)
      };
    });
    
    console.log('🔍 Error analysis:');
    console.log(`  - No pool error: ${errorMessages.hasNoPoolError ? '❌' : '✅'}`);
    console.log(`  - Insufficient funds: ${errorMessages.hasInsufficientError ? '❌' : '✅'}`);
    console.log(`  - Select token error: ${errorMessages.hasSelectTokenError ? '❌' : '✅'}`);
    
    if (errorMessages.hasNoPoolError) {
      console.log('❌ FOUND THE ISSUE: No liquidity pool error detected');
      console.log('💡 The pools are not being loaded correctly');
    }
    
    // Step 3: Check pools page
    console.log('🏊 Checking pools page...');
    await page.goto('http://localhost:3001/#/pools');
    await page.waitForTimeout(2000);
    
    const poolsPageContent = await page.evaluate(() => {
      return {
        hasPoolsTitle: document.body.innerText.includes('Liquidity Pools'),
        hasUSDCETH: document.body.innerText.includes('USDC/ETH'),
        hasEmptyState: document.body.innerText.includes('No pools'),
        bodyText: document.body.innerText.substring(0, 500)
      };
    });
    
    console.log('🔍 Pools page analysis:');
    console.log(`  - Has pools title: ${poolsPageContent.hasPoolsTitle ? '✅' : '❌'}`);
    console.log(`  - Has USDC/ETH pool: ${poolsPageContent.hasUSDCETH ? '✅' : '❌'}`);
    console.log(`  - Shows empty state: ${poolsPageContent.hasEmptyState ? '❌' : '✅'}`);
    
    // Step 4: Debug data loading
    const dataDebug = await page.evaluate(() => {
      return {
        localStorageTokens: localStorage.getItem('canton-amm-mock-tokens'),
        localStoragePools: localStorage.getItem('canton-amm-mock-pools'),
        localStorageStore: localStorage.getItem('canton-amm-store'),
        consoleErrors: [] // We'll capture these separately
      };
    });
    
    console.log('💾 Data debug:');
    console.log(`  - Tokens in localStorage: ${dataDebug.localStorageTokens ? '✅' : '❌'}`);
    console.log(`  - Pools in localStorage: ${dataDebug.localStoragePools ? '✅' : '❌'}`);
    console.log(`  - App store: ${dataDebug.localStorageStore ? '✅' : '❌'}`);
    
    if (dataDebug.localStorageTokens) {
      const tokens = JSON.parse(dataDebug.localStorageTokens);
      console.log(`  - Token count: ${tokens.length}`);
    }
    
    if (dataDebug.localStoragePools) {
      const pools = JSON.parse(dataDebug.localStoragePools);
      console.log(`  - Pool count: ${pools.length}`);
    }
    
    console.log('\n🎯 DIAGNOSIS COMPLETE');
    console.log('=====================');
    
    if (!poolsPageContent.hasUSDCETH && !errorMessages.hasNoPoolError) {
      console.log('✅ Interface working but pools not loading from mock data');
      console.log('💡 Solution: Check React Query hooks and mock API service');
    } else if (errorMessages.hasNoPoolError) {
      console.log('❌ "No liquidity pool" error confirmed');
      console.log('💡 Solution: Fix pool matching logic in swap quote calculation');
    } else {
      console.log('✅ Everything seems to be working!');
    }
  });

  test('🔐 Debug Canton Authentication', async ({ page }) => {
    console.log('🔐 DEBUGGING CANTON AUTHENTICATION');
    console.log('==================================');
    
    await page.goto('http://localhost:3001');
    await page.waitForTimeout(2000);
    
    // Test Canton API directly from browser
    const authTest = await page.evaluate(async () => {
      const results = [];
      
      // Test 1: No auth
      try {
        const response = await fetch('http://localhost:7575/v1/query', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ templateIds: [] })
        });
        results.push({
          test: 'No Auth',
          status: response.status,
          ok: response.ok,
          body: await response.text()
        });
      } catch (error) {
        results.push({
          test: 'No Auth',
          error: error.message
        });
      }
      
      // Test 2: Simple Bearer token
      try {
        const response = await fetch('http://localhost:7575/v1/query', {
          method: 'POST',
          headers: { 
            'Content-Type': 'application/json',
            'Authorization': 'Bearer simple-dev-token'
          },
          body: JSON.stringify({ templateIds: [] })
        });
        results.push({
          test: 'Simple Token',
          status: response.status,
          ok: response.ok,
          body: await response.text()
        });
      } catch (error) {
        results.push({
          test: 'Simple Token',
          error: error.message
        });
      }
      
      // Test 3: Check what Canton expects
      try {
        const response = await fetch('http://localhost:7575/v1/query', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' }
        });
        const errorBody = await response.text();
        results.push({
          test: 'Error Analysis',
          status: response.status,
          body: errorBody
        });
      } catch (error) {
        results.push({
          test: 'Error Analysis',
          error: error.message
        });
      }
      
      return results;
    });
    
    console.log('🧪 Canton Authentication Tests:');
    authTest.forEach((result, index) => {
      console.log(`\n${index + 1}. ${result.test}:`);
      if (result.error) {
        console.log(`   ❌ Error: ${result.error}`);
      } else {
        console.log(`   📊 Status: ${result.status}`);
        console.log(`   📝 Response: ${result.body?.substring(0, 100)}...`);
      }
    });
    
    console.log('\n🔍 Authentication Analysis:');
    const authRequired = authTest.some(r => r.status === 401);
    const authWorking = authTest.some(r => r.status === 200);
    
    if (authRequired && !authWorking) {
      console.log('🔐 Canton requires authentication');
      console.log('💡 Solutions:');
      console.log('  1. Use Canton in unsafe mode for development');
      console.log('  2. Generate proper JWT tokens');
      console.log('  3. Configure Canton with auth-services = [{ type = jwt-rs-256-unsafe }]');
    } else if (authWorking) {
      console.log('✅ Authentication working!');
    } else {
      console.log('❌ Canton API not responding properly');
    }
  });

  test('🎮 Complete swap test with debugging', async ({ page }) => {
    console.log('🎮 COMPLETE SWAP TEST WITH DEBUGGING');
    console.log('====================================');
    
    await page.goto('http://localhost:3001');
    
    // Inject data and force mock mode
    await page.evaluate(() => {
      const mockTokens = [
        { symbol: 'USDC', name: 'USD Coin', decimals: 6, balance: 10000.50, contractId: 'mock-usdc-001' },
        { symbol: 'ETH', name: 'Ethereum', decimals: 18, balance: 25.75, contractId: 'mock-eth-001' }
      ];
      
      const mockPools = [
        {
          contractId: 'mock-pool-usdc-eth-001',
          tokenA: { symbol: 'USDC', name: 'USD Coin', decimals: 6 },
          tokenB: { symbol: 'ETH', name: 'Ethereum', decimals: 18 },
          reserveA: 50000.0,
          reserveB: 25.0,
          totalLiquidity: 1118.03,
          feeRate: 0.003,
          apr: 12.5,
          volume24h: 125000
        }
      ];
      
      localStorage.setItem('canton-amm-mock-tokens', JSON.stringify(mockTokens));
      localStorage.setItem('canton-amm-mock-pools', JSON.stringify(mockPools));
      
      // Force mock mode
      const storeData = {
        isConnected: false,
        participantId: 'amm_participant',
        currentParty: 'Alice',
        slippage: 0.5,
        deadline: 20,
        isExpertMode: false,
        theme: 'light'
      };
      localStorage.setItem('canton-amm-store', JSON.stringify(storeData));
      
      console.log('Force mock mode activated');
    });
    
    await page.reload();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);
    
    console.log('✅ Page reloaded with mock data');
    
    // Go to swap page
    await page.goto('http://localhost:3001/#/swap');
    await page.waitForTimeout(2000);
    
    // Debug what's visible
    const swapPageDebug = await page.evaluate(() => {
      return {
        hasSwapTitle: document.body.innerText.includes('Swap'),
        hasFromField: document.body.innerText.includes('From'),
        hasToField: document.body.innerText.includes('To'),
        selectButtonCount: document.querySelectorAll('button').length,
        inputCount: document.querySelectorAll('input[type="number"]').length,
        bodySnippet: document.body.innerText.substring(0, 800)
      };
    });
    
    console.log('🔍 Swap page debug:');
    console.log(`  - Has Swap title: ${swapPageDebug.hasSwapTitle ? '✅' : '❌'}`);
    console.log(`  - Has From field: ${swapPageDebug.hasFromField ? '✅' : '❌'}`);
    console.log(`  - Has To field: ${swapPageDebug.hasToField ? '✅' : '❌'}`);
    console.log(`  - Select buttons: ${swapPageDebug.selectButtonCount}`);
    console.log(`  - Input fields: ${swapPageDebug.inputCount}`);
    
    // Try to select tokens step by step
    console.log('🔄 Attempting token selection...');
    
    const allButtons = await page.locator('button').all();
    let selectButtonsFound = 0;
    
    for (let i = 0; i < allButtons.length; i++) {
      const buttonText = await allButtons[i].textContent();
      if (buttonText && buttonText.includes('Select')) {
        selectButtonsFound++;
        console.log(`Found select button ${selectButtonsFound}: "${buttonText}"`);
        
        if (selectButtonsFound === 1) {
          // First select button - choose USDC
          try {
            await allButtons[i].click();
            await page.waitForTimeout(1000);
            
            const usdcOption = page.locator('button').filter({ hasText: 'USDC' });
            if (await usdcOption.count() > 0) {
              await usdcOption.first().click();
              console.log('✅ USDC selected successfully');
            }
          } catch (e) {
            console.log(`❌ Error selecting USDC: ${e.message}`);
          }
        }
      }
    }
    
    // Enter amount and check for quote
    const numberInputs = await page.locator('input[type="number"]').all();
    if (numberInputs.length > 0) {
      await numberInputs[0].fill('10');
      await page.waitForTimeout(2000);
      
      if (numberInputs.length > 1) {
        const outputValue = await numberInputs[1].inputValue();
        console.log(`📊 Quote calculated: 10 USDC → ${outputValue} ETH`);
        
        if (parseFloat(outputValue) > 0) {
          console.log('✅ AMM calculations working!');
        } else {
          console.log('❌ No quote calculated - pool matching issue');
        }
      }
    }
    
    // Final diagnosis
    const finalCheck = await page.evaluate(() => {
      const bodyText = document.body.innerText;
      return {
        showsNoPool: bodyText.includes('No liquidity pool') || bodyText.includes('no pool'),
        showsSelectTokens: bodyText.includes('Select Tokens'),
        showsEnterAmount: bodyText.includes('Enter Amount'),
        showsSwapButton: bodyText.includes('Swap') && !bodyText.includes('Select'),
        connectionStatus: bodyText.includes('Connected') ? 'Connected' : 
                         bodyText.includes('Disconnected') ? 'Disconnected' : 'Unknown'
      };
    });
    
    console.log('\n🎯 FINAL DIAGNOSIS:');
    console.log('===================');
    console.log(`Connection Status: ${finalCheck.connectionStatus}`);
    console.log(`No Pool Error: ${finalCheck.showsNoPool ? '❌ YES' : '✅ NO'}`);
    console.log(`Select Tokens: ${finalCheck.showsSelectTokens ? '⚠️ YES' : '✅ NO'}`);
    console.log(`Enter Amount: ${finalCheck.showsEnterAmount ? '⚠️ YES' : '✅ NO'}`);
    console.log(`Swap Button: ${finalCheck.showsSwapButton ? '✅ YES' : '❌ NO'}`);
    
    if (finalCheck.showsNoPool) {
      console.log('\n💡 SOLUTION FOR "No liquidity pool" ERROR:');
      console.log('1. Check useSwapQuote hook pool matching logic');
      console.log('2. Verify mock pools data structure');
      console.log('3. Ensure pools state is updated correctly');
    }
    
    await page.screenshot({ path: 'swap-debug.png', fullPage: true });
    console.log('📸 Debug screenshot saved: swap-debug.png');
  });
});

