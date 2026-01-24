package clearportx_amm_drain_credit.amm.pool;

import clearportx_amm_drain_credit.lptoken.lptoken.LPToken;
import clearportx_amm_drain_credit.token.token.Token;
import com.digitalasset.transcode.java.Choice;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.JavaConverters;
import com.digitalasset.transcode.java.Party;
import com.digitalasset.transcode.java.Template;
import com.digitalasset.transcode.java.Unit;
import com.digitalasset.transcode.java.Utils;
import com.digitalasset.transcode.schema.Descriptor;
import com.digitalasset.transcode.schema.DynamicValue;
import com.digitalasset.transcode.schema.Identifier;
import daml_prim_da_types.da.types.Tuple2;
import daml_prim_da_types.da.types.Tuple3;
import daml_stdlib_da_time_types.da.time.types.RelTime;
import java.lang.Boolean;
import java.lang.Long;
import java.lang.Object;
import java.lang.String;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class Pool implements Template {

  public Pool(
      Party setPoolOperator,
      Party setPoolParty,
      Party setLpIssuer,
      Party setIssuerA,
      Party setIssuerB,
      String setSymbolA,
      String setSymbolB,
      Long setFeeBps,
      String setPoolId,
      RelTime setMaxTTL,
      BigDecimal setTotalLPSupply,
      BigDecimal setReserveA,
      BigDecimal setReserveB,
      Optional<ContractId<Token>> setTokenACid,
      Optional<ContractId<Token>> setTokenBCid,
      Party setProtocolFeeReceiver,
      Long setMaxInBps,
      Long setMaxOutBps,
      List<Party> setExtraObservers) {
    getPoolOperator = setPoolOperator;
    getPoolParty = setPoolParty;
    getLpIssuer = setLpIssuer;
    getIssuerA = setIssuerA;
    getIssuerB = setIssuerB;
    getSymbolA = setSymbolA;
    getSymbolB = setSymbolB;
    getFeeBps = setFeeBps;
    getPoolId = setPoolId;
    getMaxTTL = setMaxTTL;
    getTotalLPSupply = setTotalLPSupply;
    getReserveA = setReserveA;
    getReserveB = setReserveB;
    getTokenACid = setTokenACid;
    getTokenBCid = setTokenBCid;
    getProtocolFeeReceiver = setProtocolFeeReceiver;
    getMaxInBps = setMaxInBps;
    getMaxOutBps = setMaxOutBps;
    getExtraObservers = setExtraObservers;
  }

  public final Party getPoolOperator;

  public final Party getPoolParty;

  public final Party getLpIssuer;

  public final Party getIssuerA;

  public final Party getIssuerB;

  public final String getSymbolA;

  public final String getSymbolB;

  public final Long getFeeBps;

  public final String getPoolId;

  public final RelTime getMaxTTL;

  public final BigDecimal getTotalLPSupply;

  public final BigDecimal getReserveA;

  public final BigDecimal getReserveB;

  public final Optional<ContractId<Token>> getTokenACid;

  public final Optional<ContractId<Token>> getTokenBCid;

  public final Party getProtocolFeeReceiver;

  public final Long getMaxInBps;

  public final Long getMaxOutBps;

  public final List<Party> getExtraObservers;

  public static final Function<Object, Pool> fromDynamicValue =
      value ->
          DynamicValue.withRecordIterator(
              value,
              19,
              record ->
                  new Pool(
                      JavaConverters.fromParty.apply(record.next()),
                      JavaConverters.fromParty.apply(record.next()),
                      JavaConverters.fromParty.apply(record.next()),
                      JavaConverters.fromParty.apply(record.next()),
                      JavaConverters.fromParty.apply(record.next()),
                      JavaConverters.fromText.apply(record.next()),
                      JavaConverters.fromText.apply(record.next()),
                      JavaConverters.fromInt64.apply(record.next()),
                      JavaConverters.fromText.apply(record.next()),
                      RelTime.fromDynamicValue.apply(record.next()),
                      JavaConverters.fromNumeric(10).apply(record.next()),
                      JavaConverters.fromNumeric(10).apply(record.next()),
                      JavaConverters.fromNumeric(10).apply(record.next()),
                      JavaConverters.fromOptional(
                              JavaConverters.fromContractId(Token.fromDynamicValue))
                          .apply(record.next()),
                      JavaConverters.fromOptional(
                              JavaConverters.fromContractId(Token.fromDynamicValue))
                          .apply(record.next()),
                      JavaConverters.fromParty.apply(record.next()),
                      JavaConverters.fromInt64.apply(record.next()),
                      JavaConverters.fromInt64.apply(record.next()),
                      JavaConverters.fromList(JavaConverters.fromParty).apply(record.next())));

  public static final Function<Pool, Object> toDynamicValue =
      value ->
          DynamicValue.Record(
              new Object[] {
                JavaConverters.toParty.apply(value.getPoolOperator),
                JavaConverters.toParty.apply(value.getPoolParty),
                JavaConverters.toParty.apply(value.getLpIssuer),
                JavaConverters.toParty.apply(value.getIssuerA),
                JavaConverters.toParty.apply(value.getIssuerB),
                JavaConverters.toText.apply(value.getSymbolA),
                JavaConverters.toText.apply(value.getSymbolB),
                JavaConverters.toInt64.apply(value.getFeeBps),
                JavaConverters.toText.apply(value.getPoolId),
                RelTime.toDynamicValue.apply(value.getMaxTTL),
                JavaConverters.toNumeric(10).apply(value.getTotalLPSupply),
                JavaConverters.toNumeric(10).apply(value.getReserveA),
                JavaConverters.toNumeric(10).apply(value.getReserveB),
                JavaConverters.toOptional(JavaConverters.toContractId(Token.toDynamicValue))
                    .apply(value.getTokenACid),
                JavaConverters.toOptional(JavaConverters.toContractId(Token.toDynamicValue))
                    .apply(value.getTokenBCid),
                JavaConverters.toParty.apply(value.getProtocolFeeReceiver),
                JavaConverters.toInt64.apply(value.getMaxInBps),
                JavaConverters.toInt64.apply(value.getMaxOutBps),
                JavaConverters.toList(JavaConverters.toParty).apply(value.getExtraObservers)
              });

  public static final Descriptor.Constructor descriptor =
      Descriptor.constructor(
          clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool,
          new String[] {},
          () ->
              Descriptor.record(
                  Utils.pair("poolOperator", Descriptor.party()),
                  Utils.pair("poolParty", Descriptor.party()),
                  Utils.pair("lpIssuer", Descriptor.party()),
                  Utils.pair("issuerA", Descriptor.party()),
                  Utils.pair("issuerB", Descriptor.party()),
                  Utils.pair("symbolA", Descriptor.text()),
                  Utils.pair("symbolB", Descriptor.text()),
                  Utils.pair("feeBps", Descriptor.int64()),
                  Utils.pair("poolId", Descriptor.text()),
                  Utils.pair("maxTTL", RelTime.descriptor),
                  Utils.pair("totalLPSupply", Descriptor.numeric(10)),
                  Utils.pair("reserveA", Descriptor.numeric(10)),
                  Utils.pair("reserveB", Descriptor.numeric(10)),
                  Utils.pair(
                      "tokenACid", Descriptor.optional(Descriptor.contractId(Token.descriptor))),
                  Utils.pair(
                      "tokenBCid", Descriptor.optional(Descriptor.contractId(Token.descriptor))),
                  Utils.pair("protocolFeeReceiver", Descriptor.party()),
                  Utils.pair("maxInBps", Descriptor.int64()),
                  Utils.pair("maxOutBps", Descriptor.int64()),
                  Utils.pair("extraObservers", Descriptor.list(Descriptor.party()))));

  public static final Identifier TEMPLATE_ID =
      clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

  public final Identifier templateId() {
    return TEMPLATE_ID;
  }

  public static final class AtomicSwap
      implements Choice<Pool, Tuple2<ContractId<Token>, ContractId<Pool>>> {

    public AtomicSwap(
        Party setTrader,
        ContractId<Token> setTraderInputTokenCid,
        String setInputSymbol,
        BigDecimal setInputAmount,
        String setOutputSymbol,
        BigDecimal setMinOutput,
        Long setMaxPriceImpactBps,
        Instant setDeadline) {
      getTrader = setTrader;
      getTraderInputTokenCid = setTraderInputTokenCid;
      getInputSymbol = setInputSymbol;
      getInputAmount = setInputAmount;
      getOutputSymbol = setOutputSymbol;
      getMinOutput = setMinOutput;
      getMaxPriceImpactBps = setMaxPriceImpactBps;
      getDeadline = setDeadline;
    }

    public final Party getTrader;

    public final ContractId<Token> getTraderInputTokenCid;

    public final String getInputSymbol;

    public final BigDecimal getInputAmount;

    public final String getOutputSymbol;

    public final BigDecimal getMinOutput;

    public final Long getMaxPriceImpactBps;

    public final Instant getDeadline;

    public static final Function<Object, AtomicSwap> fromDynamicValue =
        value ->
            DynamicValue.withRecordIterator(
                value,
                8,
                record ->
                    new AtomicSwap(
                        JavaConverters.fromParty.apply(record.next()),
                        JavaConverters.fromContractId(Token.fromDynamicValue).apply(record.next()),
                        JavaConverters.fromText.apply(record.next()),
                        JavaConverters.fromNumeric(10).apply(record.next()),
                        JavaConverters.fromText.apply(record.next()),
                        JavaConverters.fromNumeric(10).apply(record.next()),
                        JavaConverters.fromInt64.apply(record.next()),
                        JavaConverters.fromTimestamp.apply(record.next())));

    public static final Function<AtomicSwap, Object> toDynamicValue =
        value ->
            DynamicValue.Record(
                new Object[] {
                  JavaConverters.toParty.apply(value.getTrader),
                  JavaConverters.toContractId(Token.toDynamicValue)
                      .apply(value.getTraderInputTokenCid),
                  JavaConverters.toText.apply(value.getInputSymbol),
                  JavaConverters.toNumeric(10).apply(value.getInputAmount),
                  JavaConverters.toText.apply(value.getOutputSymbol),
                  JavaConverters.toNumeric(10).apply(value.getMinOutput),
                  JavaConverters.toInt64.apply(value.getMaxPriceImpactBps),
                  JavaConverters.toTimestamp.apply(value.getDeadline)
                });

    public static final Descriptor.Constructor descriptor =
        Descriptor.constructor(
            clearportx_amm_drain_credit.Identifiers.AMM_Pool__AtomicSwap,
            new String[] {},
            () ->
                Descriptor.record(
                    Utils.pair("trader", Descriptor.party()),
                    Utils.pair("traderInputTokenCid", Descriptor.contractId(Token.descriptor)),
                    Utils.pair("inputSymbol", Descriptor.text()),
                    Utils.pair("inputAmount", Descriptor.numeric(10)),
                    Utils.pair("outputSymbol", Descriptor.text()),
                    Utils.pair("minOutput", Descriptor.numeric(10)),
                    Utils.pair("maxPriceImpactBps", Descriptor.int64()),
                    Utils.pair("deadline", Descriptor.timestamp())));

    public static final Identifier TEMPLATE_ID =
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

    public final Identifier templateId() {
      return TEMPLATE_ID;
    }

    public static final String CHOICE_NAME = "AtomicSwap";

    public final String choiceName() {
      return CHOICE_NAME;
    }
  }

  public static final class VerifyReserves implements Choice<Pool, Tuple2<Boolean, String>> {

    public VerifyReserves() {}

    public static final Function<Object, VerifyReserves> fromDynamicValue =
        value -> DynamicValue.withRecordIterator(value, 0, record -> new VerifyReserves());

    public static final Function<VerifyReserves, Object> toDynamicValue =
        value -> DynamicValue.Record(new Object[] {});

    public static final Descriptor.Constructor descriptor =
        Descriptor.constructor(
            clearportx_amm_drain_credit.Identifiers.AMM_Pool__VerifyReserves,
            new String[] {},
            () -> Descriptor.record());

    public static final Identifier TEMPLATE_ID =
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

    public final Identifier templateId() {
      return TEMPLATE_ID;
    }

    public static final String CHOICE_NAME = "VerifyReserves";

    public final String choiceName() {
      return CHOICE_NAME;
    }
  }

  public static final class GetSupply implements Choice<Pool, BigDecimal> {

    public GetSupply() {}

    public static final Function<Object, GetSupply> fromDynamicValue =
        value -> DynamicValue.withRecordIterator(value, 0, record -> new GetSupply());

    public static final Function<GetSupply, Object> toDynamicValue =
        value -> DynamicValue.Record(new Object[] {});

    public static final Descriptor.Constructor descriptor =
        Descriptor.constructor(
            clearportx_amm_drain_credit.Identifiers.AMM_Pool__GetSupply,
            new String[] {},
            () -> Descriptor.record());

    public static final Identifier TEMPLATE_ID =
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

    public final Identifier templateId() {
      return TEMPLATE_ID;
    }

    public static final String CHOICE_NAME = "GetSupply";

    public final String choiceName() {
      return CHOICE_NAME;
    }
  }

  public static final class GetReservesForPool
      implements Choice<Pool, Tuple2<BigDecimal, BigDecimal>> {

    public GetReservesForPool() {}

    public static final Function<Object, GetReservesForPool> fromDynamicValue =
        value -> DynamicValue.withRecordIterator(value, 0, record -> new GetReservesForPool());

    public static final Function<GetReservesForPool, Object> toDynamicValue =
        value -> DynamicValue.Record(new Object[] {});

    public static final Descriptor.Constructor descriptor =
        Descriptor.constructor(
            clearportx_amm_drain_credit.Identifiers.AMM_Pool__GetReservesForPool,
            new String[] {},
            () -> Descriptor.record());

    public static final Identifier TEMPLATE_ID =
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

    public final Identifier templateId() {
      return TEMPLATE_ID;
    }

    public static final String CHOICE_NAME = "GetReservesForPool";

    public final String choiceName() {
      return CHOICE_NAME;
    }
  }

  public static final class AddLiquidity
      implements Choice<Pool, Tuple2<ContractId<LPToken>, ContractId<Pool>>> {

    public AddLiquidity(
        Party setProvider,
        ContractId<Token> setTokenACid,
        ContractId<Token> setTokenBCid,
        BigDecimal setAmountA,
        BigDecimal setAmountB,
        BigDecimal setMinLPTokens,
        Instant setDeadline) {
      getProvider = setProvider;
      getTokenACid = setTokenACid;
      getTokenBCid = setTokenBCid;
      getAmountA = setAmountA;
      getAmountB = setAmountB;
      getMinLPTokens = setMinLPTokens;
      getDeadline = setDeadline;
    }

    public final Party getProvider;

    public final ContractId<Token> getTokenACid;

    public final ContractId<Token> getTokenBCid;

    public final BigDecimal getAmountA;

    public final BigDecimal getAmountB;

    public final BigDecimal getMinLPTokens;

    public final Instant getDeadline;

    public static final Function<Object, AddLiquidity> fromDynamicValue =
        value ->
            DynamicValue.withRecordIterator(
                value,
                7,
                record ->
                    new AddLiquidity(
                        JavaConverters.fromParty.apply(record.next()),
                        JavaConverters.fromContractId(Token.fromDynamicValue).apply(record.next()),
                        JavaConverters.fromContractId(Token.fromDynamicValue).apply(record.next()),
                        JavaConverters.fromNumeric(10).apply(record.next()),
                        JavaConverters.fromNumeric(10).apply(record.next()),
                        JavaConverters.fromNumeric(10).apply(record.next()),
                        JavaConverters.fromTimestamp.apply(record.next())));

    public static final Function<AddLiquidity, Object> toDynamicValue =
        value ->
            DynamicValue.Record(
                new Object[] {
                  JavaConverters.toParty.apply(value.getProvider),
                  JavaConverters.toContractId(Token.toDynamicValue).apply(value.getTokenACid),
                  JavaConverters.toContractId(Token.toDynamicValue).apply(value.getTokenBCid),
                  JavaConverters.toNumeric(10).apply(value.getAmountA),
                  JavaConverters.toNumeric(10).apply(value.getAmountB),
                  JavaConverters.toNumeric(10).apply(value.getMinLPTokens),
                  JavaConverters.toTimestamp.apply(value.getDeadline)
                });

    public static final Descriptor.Constructor descriptor =
        Descriptor.constructor(
            clearportx_amm_drain_credit.Identifiers.AMM_Pool__AddLiquidity,
            new String[] {},
            () ->
                Descriptor.record(
                    Utils.pair("provider", Descriptor.party()),
                    Utils.pair("tokenACid", Descriptor.contractId(Token.descriptor)),
                    Utils.pair("tokenBCid", Descriptor.contractId(Token.descriptor)),
                    Utils.pair("amountA", Descriptor.numeric(10)),
                    Utils.pair("amountB", Descriptor.numeric(10)),
                    Utils.pair("minLPTokens", Descriptor.numeric(10)),
                    Utils.pair("deadline", Descriptor.timestamp())));

    public static final Identifier TEMPLATE_ID =
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

    public final Identifier templateId() {
      return TEMPLATE_ID;
    }

    public static final String CHOICE_NAME = "AddLiquidity";

    public final String choiceName() {
      return CHOICE_NAME;
    }
  }

  public static final class ArchiveAndUpdateReserves implements Choice<Pool, ContractId<Pool>> {

    public ArchiveAndUpdateReserves(
        BigDecimal setUpdatedReserveA,
        BigDecimal setUpdatedReserveB,
        Optional<ContractId<Token>> setUpdatedTokenACid,
        Optional<ContractId<Token>> setUpdatedTokenBCid) {
      getUpdatedReserveA = setUpdatedReserveA;
      getUpdatedReserveB = setUpdatedReserveB;
      getUpdatedTokenACid = setUpdatedTokenACid;
      getUpdatedTokenBCid = setUpdatedTokenBCid;
    }

    public final BigDecimal getUpdatedReserveA;

    public final BigDecimal getUpdatedReserveB;

    public final Optional<ContractId<Token>> getUpdatedTokenACid;

    public final Optional<ContractId<Token>> getUpdatedTokenBCid;

    public static final Function<Object, ArchiveAndUpdateReserves> fromDynamicValue =
        value ->
            DynamicValue.withRecordIterator(
                value,
                4,
                record ->
                    new ArchiveAndUpdateReserves(
                        JavaConverters.fromNumeric(10).apply(record.next()),
                        JavaConverters.fromNumeric(10).apply(record.next()),
                        JavaConverters.fromOptional(
                                JavaConverters.fromContractId(Token.fromDynamicValue))
                            .apply(record.next()),
                        JavaConverters.fromOptional(
                                JavaConverters.fromContractId(Token.fromDynamicValue))
                            .apply(record.next())));

    public static final Function<ArchiveAndUpdateReserves, Object> toDynamicValue =
        value ->
            DynamicValue.Record(
                new Object[] {
                  JavaConverters.toNumeric(10).apply(value.getUpdatedReserveA),
                  JavaConverters.toNumeric(10).apply(value.getUpdatedReserveB),
                  JavaConverters.toOptional(JavaConverters.toContractId(Token.toDynamicValue))
                      .apply(value.getUpdatedTokenACid),
                  JavaConverters.toOptional(JavaConverters.toContractId(Token.toDynamicValue))
                      .apply(value.getUpdatedTokenBCid)
                });

    public static final Descriptor.Constructor descriptor =
        Descriptor.constructor(
            clearportx_amm_drain_credit.Identifiers.AMM_Pool__ArchiveAndUpdateReserves,
            new String[] {},
            () ->
                Descriptor.record(
                    Utils.pair("updatedReserveA", Descriptor.numeric(10)),
                    Utils.pair("updatedReserveB", Descriptor.numeric(10)),
                    Utils.pair(
                        "updatedTokenACid",
                        Descriptor.optional(Descriptor.contractId(Token.descriptor))),
                    Utils.pair(
                        "updatedTokenBCid",
                        Descriptor.optional(Descriptor.contractId(Token.descriptor)))));

    public static final Identifier TEMPLATE_ID =
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

    public final Identifier templateId() {
      return TEMPLATE_ID;
    }

    public static final String CHOICE_NAME = "ArchiveAndUpdateReserves";

    public final String choiceName() {
      return CHOICE_NAME;
    }
  }

  public static final class GrantVisibility implements Choice<Pool, ContractId<Pool>> {

    public GrantVisibility(Party setNewObserver) {
      getNewObserver = setNewObserver;
    }

    public final Party getNewObserver;

    public static final Function<Object, GrantVisibility> fromDynamicValue =
        value ->
            DynamicValue.withRecordIterator(
                value,
                1,
                record -> new GrantVisibility(JavaConverters.fromParty.apply(record.next())));

    public static final Function<GrantVisibility, Object> toDynamicValue =
        value ->
            DynamicValue.Record(
                new Object[] {JavaConverters.toParty.apply(value.getNewObserver)});

    public static final Descriptor.Constructor descriptor =
        Descriptor.constructor(
            clearportx_amm_drain_credit.Identifiers.AMM_Pool__GrantVisibility,
            new String[] {},
            () -> Descriptor.record(Utils.pair("newObserver", Descriptor.party())));

    public static final Identifier TEMPLATE_ID =
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

    public final Identifier templateId() {
      return TEMPLATE_ID;
    }

    public static final String CHOICE_NAME = "GrantVisibility";

    public final String choiceName() {
      return CHOICE_NAME;
    }
  }

  public static final class GetSpotPrice implements Choice<Pool, Tuple2<BigDecimal, BigDecimal>> {

    public GetSpotPrice() {}

    public static final Function<Object, GetSpotPrice> fromDynamicValue =
        value -> DynamicValue.withRecordIterator(value, 0, record -> new GetSpotPrice());

    public static final Function<GetSpotPrice, Object> toDynamicValue =
        value -> DynamicValue.Record(new Object[] {});

    public static final Descriptor.Constructor descriptor =
        Descriptor.constructor(
            clearportx_amm_drain_credit.Identifiers.AMM_Pool__GetSpotPrice,
            new String[] {},
            () -> Descriptor.record());

    public static final Identifier TEMPLATE_ID =
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

    public final Identifier templateId() {
      return TEMPLATE_ID;
    }

    public static final String CHOICE_NAME = "GetSpotPrice";

    public final String choiceName() {
      return CHOICE_NAME;
    }
  }

  public static final class Archive implements Choice<Pool, Unit> {

    public Archive() {}

    public static final Function<Object, Archive> fromDynamicValue =
        value -> DynamicValue.withRecordIterator(value, 0, record -> new Archive());

    public static final Function<Archive, Object> toDynamicValue =
        value -> DynamicValue.Record(new Object[] {});

    public static final Descriptor.Constructor descriptor =
        Descriptor.constructor(
            ghc_stdlib_da_internal_template.Identifiers.DA_Internal_Template__Archive,
            new String[] {},
            () -> Descriptor.record());

    public static final Identifier TEMPLATE_ID =
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

    public final Identifier templateId() {
      return TEMPLATE_ID;
    }

    public static final String CHOICE_NAME = "Archive";

    public final String choiceName() {
      return CHOICE_NAME;
    }
  }

  public static final class RemoveLiquidity
      implements Choice<Pool, Tuple3<ContractId<Token>, ContractId<Token>, ContractId<Pool>>> {

    public RemoveLiquidity(
        Party setProvider,
        ContractId<LPToken> setLpTokenCid,
        BigDecimal setLpTokenAmount,
        BigDecimal setMinAmountA,
        BigDecimal setMinAmountB,
        Instant setDeadline) {
      getProvider = setProvider;
      getLpTokenCid = setLpTokenCid;
      getLpTokenAmount = setLpTokenAmount;
      getMinAmountA = setMinAmountA;
      getMinAmountB = setMinAmountB;
      getDeadline = setDeadline;
    }

    public final Party getProvider;

    public final ContractId<LPToken> getLpTokenCid;

    public final BigDecimal getLpTokenAmount;

    public final BigDecimal getMinAmountA;

    public final BigDecimal getMinAmountB;

    public final Instant getDeadline;

    public static final Function<Object, RemoveLiquidity> fromDynamicValue =
        value ->
            DynamicValue.withRecordIterator(
                value,
                6,
                record ->
                    new RemoveLiquidity(
                        JavaConverters.fromParty.apply(record.next()),
                        JavaConverters.fromContractId(LPToken.fromDynamicValue)
                            .apply(record.next()),
                        JavaConverters.fromNumeric(10).apply(record.next()),
                        JavaConverters.fromNumeric(10).apply(record.next()),
                        JavaConverters.fromNumeric(10).apply(record.next()),
                        JavaConverters.fromTimestamp.apply(record.next())));

    public static final Function<RemoveLiquidity, Object> toDynamicValue =
        value ->
            DynamicValue.Record(
                new Object[] {
                  JavaConverters.toParty.apply(value.getProvider),
                  JavaConverters.toContractId(LPToken.toDynamicValue).apply(value.getLpTokenCid),
                  JavaConverters.toNumeric(10).apply(value.getLpTokenAmount),
                  JavaConverters.toNumeric(10).apply(value.getMinAmountA),
                  JavaConverters.toNumeric(10).apply(value.getMinAmountB),
                  JavaConverters.toTimestamp.apply(value.getDeadline)
                });

    public static final Descriptor.Constructor descriptor =
        Descriptor.constructor(
            clearportx_amm_drain_credit.Identifiers.AMM_Pool__RemoveLiquidity,
            new String[] {},
            () ->
                Descriptor.record(
                    Utils.pair("provider", Descriptor.party()),
                    Utils.pair("lpTokenCid", Descriptor.contractId(LPToken.descriptor)),
                    Utils.pair("lpTokenAmount", Descriptor.numeric(10)),
                    Utils.pair("minAmountA", Descriptor.numeric(10)),
                    Utils.pair("minAmountB", Descriptor.numeric(10)),
                    Utils.pair("deadline", Descriptor.timestamp())));

    public static final Identifier TEMPLATE_ID =
        clearportx_amm_drain_credit.Identifiers.AMM_Pool__Pool;

    public final Identifier templateId() {
      return TEMPLATE_ID;
    }

    public static final String CHOICE_NAME = "RemoveLiquidity";

    public final String choiceName() {
      return CHOICE_NAME;
    }
  }
}
