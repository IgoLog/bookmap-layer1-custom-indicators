package velox.api.layer1.simpledemo.customevents;

import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import velox.api.layer1.Layer1ApiAdminAdapter;
import velox.api.layer1.Layer1ApiFinishable;
import velox.api.layer1.Layer1ApiInstrumentAdapter;
import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.Layer1CustomPanelsGetter;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1DuplicateInstrumentsAware;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.ListenableHelper;
import velox.api.layer1.data.BalanceInfo;
import velox.api.layer1.data.ExecutionInfo;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.MarketMode;
import velox.api.layer1.data.OrderInfoUpdate;
import velox.api.layer1.data.StatusInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.layers.strategies.interfaces.CalculatedResultListener;
import velox.api.layer1.layers.strategies.interfaces.CustomEventAggregatble;
import velox.api.layer1.layers.strategies.interfaces.CustomGeneratedEvent;
import velox.api.layer1.layers.strategies.interfaces.CustomGeneratedEventAliased;
import velox.api.layer1.layers.strategies.interfaces.InvalidateInterface;
import velox.api.layer1.layers.strategies.interfaces.Layer1IndicatorColorInterface;
import velox.api.layer1.layers.strategies.interfaces.OnlineCalculatable;
import velox.api.layer1.layers.strategies.interfaces.OnlineValueCalculatorAdapter;
import velox.api.layer1.messages.GeneratedEventInfo;
import velox.api.layer1.messages.Layer1ApiUserMessageAddStrategyUpdateGenerator;
import velox.api.layer1.messages.UserMessageLayersChainCreatedTargeted;
import velox.api.layer1.messages.indicators.DataStructureInterface;
import velox.api.layer1.messages.indicators.DataStructureInterface.TreeResponseInterval;
import velox.api.layer1.messages.indicators.IndicatorColorScheme;
import velox.api.layer1.messages.indicators.Layer1ApiDataInterfaceRequestMessage;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.messages.indicators.SettingsAccess;
import velox.api.layer1.messages.indicators.StrategyUpdateGenerator;
import velox.api.layer1.settings.Layer1ConfigSettingsInterface;
import velox.colors.ColorsChangedListener;
import velox.gui.StrategyPanel;
import velox.gui.colors.ColorsConfigItem;

@Layer1Attachable
@Layer1StrategyName("Loginov")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
@Layer1DuplicateInstrumentsAware
public class Loginov implements
        Layer1ApiFinishable,
        Layer1ApiAdminAdapter,
        Layer1ApiInstrumentAdapter,
        OnlineCalculatable,
        Layer1CustomPanelsGetter,
        Layer1ConfigSettingsInterface,
        Layer1IndicatorColorInterface {

    public static class LoginovSettings {
        public Color lineColor = LINE_COLOR_DEFAULT;
        public int lineWidth = 2;
        public double alpha = 0.5;
    }

    private static class CustomTradePriceEvent implements CustomGeneratedEvent {
        private static final long serialVersionUID = 1L;
        private final long time;
        public final double lastPrice;

        public CustomTradePriceEvent(long time, double lastPrice) {
            this.time = time;
            this.lastPrice = lastPrice;
        }

        @Override
        public long getTime() {
            return time;
        }

        @Override
        public Object clone() {
            return new CustomTradePriceEvent(time, lastPrice);
        }

        @Override
        public String toString() {
            return "[" + lastPrice + "(V)]";
        }
    }

    private static class CustomTradePriceAggregationEvent implements CustomGeneratedEvent {
        private static final long serialVersionUID = 1L;
        private final long time;
        public double lastPrice;

        public CustomTradePriceAggregationEvent(long time, double lastPrice) {
            this.time = time;
            this.lastPrice = lastPrice;
        }

        @Override
        public long getTime() {
            return time;
        }

        @Override
        public Object clone() {
            return new CustomTradePriceAggregationEvent(time, lastPrice);
        }

        @Override
        public String toString() {
            return "[" + lastPrice + "(A)]";
        }
    }

    public static final CustomEventAggregatble CUSTOM_TRADE_EVENTS_AGGREGATOR = new CustomEventAggregatble() {
        @Override
        public CustomGeneratedEvent getInitialValue(long t) {
            return new CustomTradePriceAggregationEvent(t, Double.NaN);
        }

        @Override
        public void aggregateAggregationWithValue(CustomGeneratedEvent aggregation, CustomGeneratedEvent value) {
            CustomTradePriceAggregationEvent agg = (CustomTradePriceAggregationEvent) aggregation;
            CustomTradePriceEvent val = (CustomTradePriceEvent) value;
            if (!Double.isNaN(val.lastPrice)) {
                agg.lastPrice = val.lastPrice;
            }
        }

        @Override
        public void aggregateAggregationWithAggregation(CustomGeneratedEvent aggregation1,
                CustomGeneratedEvent aggregation2) {
            CustomTradePriceAggregationEvent agg1 = (CustomTradePriceAggregationEvent) aggregation1;
            CustomTradePriceAggregationEvent agg2 = (CustomTradePriceAggregationEvent) aggregation2;
            if (!Double.isNaN(agg2.lastPrice)) {
                agg1.lastPrice = agg2.lastPrice;
            }
        }
    };

    private static final String INDICATOR_NAME = "Loginov";
    private static final String TREE_NAME = "Loginov Tree";
    private static final String LINE_COLOR_NAME = "Line color";
    private static final Color LINE_COLOR_DEFAULT = Color.RED;

    private static final Class<?>[] INTERESTING_CUSTOM_EVENTS = new Class<?>[] { CustomTradePriceEvent.class };

    private final Layer1ApiProvider provider;
    private DataStructureInterface dataStructureInterface;
    private SettingsAccess settingsAccess;

    private final Map<String, LoginovSettings> settingsMap = new ConcurrentHashMap<>();
    private final Map<String, InvalidateInterface> invalidateInterfaceMap = new ConcurrentHashMap<>();
    private final Map<String, Double> pipsMap = new ConcurrentHashMap<>();
    private final Object locker = new Object();

    public Loginov(Layer1ApiProvider provider) {
        this.provider = provider;
        ListenableHelper.addListeners(provider, this);
    }

    private double getValueFromEvent(TreeResponseInterval treeResponseInterval) {
        if (treeResponseInterval == null || treeResponseInterval.events == null) {
            return Double.NaN;
        }
        Object object = treeResponseInterval.events.get(CustomTradePriceEvent.class.toString());
        if (object instanceof CustomTradePriceAggregationEvent) {
            return ((CustomTradePriceAggregationEvent) object).lastPrice;
        }
        return Double.NaN;
    }

    @Override
    public void calculateValuesInRange(String indicatorName, String indicatorAlias, long t0, long intervalWidth,
            int intervalsNumber, CalculatedResultListener listener) {
        if (dataStructureInterface == null) {
            for (int i = 0; i < intervalsNumber; i++) {
                listener.provideResponse(Double.NaN);
            }
            listener.completed();
            return;
        }

        List<TreeResponseInterval> result = dataStructureInterface.get(
                Loginov.class, TREE_NAME, t0, intervalWidth, intervalsNumber,
                indicatorAlias, INTERESTING_CUSTOM_EVENTS);

        if (result == null || result.isEmpty()) {
            for (int i = 0; i < intervalsNumber; i++) {
                listener.provideResponse(Double.NaN);
            }
            listener.completed();
            return;
        }

        double lastValue = getValueFromEvent(result.get(0));
        for (int i = 1; i <= intervalsNumber; i++) {
            double value = getValueFromEvent(result.get(i));
            if (!Double.isNaN(value)) {
                lastValue = value;
            }
            listener.provideResponse(lastValue);
        }
        listener.completed();
    }

    @Override
    public OnlineValueCalculatorAdapter getOnlineValueCalculator(String indicatorName, String indicatorAlias,
            long time, Consumer<Object> listener, InvalidateInterface invalidateInterface) {
        invalidateInterfaceMap.put(indicatorAlias, invalidateInterface);

        if (dataStructureInterface != null) {
            List<TreeResponseInterval> init = dataStructureInterface.get(
                    Loginov.class, TREE_NAME, time, 1, 0,
                    indicatorAlias, INTERESTING_CUSTOM_EVENTS);
            if (init != null && !init.isEmpty()) {
                double initialVal = getValueFromEvent(init.get(0));
                if (!Double.isNaN(initialVal)) {
                    listener.accept(initialVal);
                }
            }
        }

        return new OnlineValueCalculatorAdapter() {
            @Override
            public void onUserMessage(Object data) {
                if (data instanceof CustomGeneratedEventAliased) {
                    CustomGeneratedEventAliased aliasedEvent = (CustomGeneratedEventAliased) data;
                    if (indicatorAlias.equals(aliasedEvent.alias) && aliasedEvent.event instanceof CustomTradePriceEvent) {
CustomTradePriceEvent event = (CustomTradePriceEvent) aliasedEvent.event;
listener.accept(event.lastPrice);
}
}
}
};
}
@Override
public void onUserMessage(Object data) {
if (data instanceof UserMessageLayersChainCreatedTargeted) {
UserMessageLayersChainCreatedTargeted message = (UserMessageLayersChainCreatedTargeted) data;
if (message.targetClass == getClass()) {
provider.sendUserMessage(new Layer1ApiDataInterfaceRequestMessage(
dsi -> this.dataStructureInterface = dsi));
provider.sendUserMessage(getGeneratorMessage(true));
addIndicator();
}
}
}
@Override
public void onInstrumentAdded(String alias, InstrumentInfo instrumentInfo) {
pipsMap.put(alias, instrumentInfo.pips);
}
@Override
public void onInstrumentRemoved(String alias) {
pipsMap.remove(alias);
invalidateInterfaceMap.remove(alias);
}
private Layer1ApiUserMessageAddStrategyUpdateGenerator getGeneratorMessage(boolean isAdd) {
return new Layer1ApiUserMessageAddStrategyUpdateGenerator(
Loginov.class,
TREE_NAME,
isAdd,
true,
new StrategyUpdateGenerator() {
private final Map<String, Double> lastCalculatedValues = new HashMap<>();
private Consumer generatedEventsConsumer;
private long currentTime = 0;
@Override
public void setGeneratedEventsConsumer(Consumer consumer) {
this.generatedEventsConsumer = consumer;
}
@Override
public Consumer getGeneratedEventsConsumer() {
return generatedEventsConsumer;
}
@Override
public void setTime(long time) {
this.currentTime = time;
}
@Override
public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {
if (generatedEventsConsumer == null) {
return;
}
double alpha = getSettingsFor(alias).alpha;
Double prev = lastCalculatedValues.get(alias);
double newValue = (prev == null) ? price : (alpha * price + (1.0 - alpha) * prev);
lastCalculatedValues.put(alias, newValue);
double pips = pipsMap.getOrDefault(alias, 1.0);
CustomTradePriceEvent event = new CustomTradePriceEvent(currentTime, newValue * pips);
generatedEventsConsumer.accept(new CustomGeneratedEventAliased(event, alias));
}
@Override
public void onInstrumentAdded(String alias, InstrumentInfo instrumentInfo) {
pipsMap.put(alias, instrumentInfo.pips);
}
@Override
public void onInstrumentRemoved(String alias) {
lastCalculatedValues.remove(alias);
}
@Override
public void onStatus(StatusInfo statusInfo) {}
@Override
public void onOrderUpdated(OrderInfoUpdate orderInfoUpdate) {}
@Override
public void onOrderExecuted(ExecutionInfo executionInfo) {}
@Override
public void onBalance(BalanceInfo balanceInfo) {}
@Override
public void onMarketMode(String alias, MarketMode marketMode) {}
@Override
public void onDepth(String alias, boolean isBid, int price, int size) {}
@Override
public void onInstrumentNotFound(String symbol, String exchange, String type) {}
@Override
public void onInstrumentAlreadySubscribed(String symbol, String exchange, String type) {}
@Override
public void onUserMessage(Object data) {}
},
new GeneratedEventInfo[] {
new GeneratedEventInfo(
CustomTradePriceEvent.class,
CustomTradePriceAggregationEvent.class,
CUSTOM_TRADE_EVENTS_AGGREGATOR)
});
}
private void addIndicator() {
Layer1ApiUserMessageModifyIndicator message = Layer1ApiUserMessageModifyIndicator.builder(
Loginov.class, INDICATOR_NAME)
.setIsAdd(true)
.setGraphType(GraphType.PRIMARY)
.setOnlineCalculatable(this)
.setIndicatorColorScheme(new IndicatorColorScheme() {
@Override
public ColorDescription[] getColors() {
return new ColorDescription[] {
new ColorDescription(Loginov.class, LINE_COLOR_NAME,
LINE_COLOR_DEFAULT, false)
};
}
@Override
public String getColorFor(Double value) {
return LINE_COLOR_NAME;
}
@Override
public ColorIntervalResponse getColorIntervalsList(double valueFrom, double valueTo) {
return new ColorIntervalResponse(new String[] { LINE_COLOR_NAME }, new double[] {});
}
})
.build();
provider.sendUserMessage(message);
}
private void rebuildGenerator() {
provider.sendUserMessage(getGeneratorMessage(false));
provider.sendUserMessage(getGeneratorMessage(true));
}
@Override
public void finish() {
provider.sendUserMessage(getGeneratorMessage(false));
provider.sendUserMessage(Layer1ApiUserMessageModifyIndicator.builder(
Loginov.class, INDICATOR_NAME)
.setIsAdd(false)
.build());
ListenableHelper.removeListeners(provider, this);
}
private LoginovSettings getSettingsFor(String alias) {
synchronized (locker) {
LoginovSettings settings = settingsMap.get(alias);
if (settings == null) {
if (settingsAccess != null) {
settings = (LoginovSettings) settingsAccess.getSettings(
alias, INDICATOR_NAME, LoginovSettings.class);
}
if (settings == null) {
settings = new LoginovSettings();
}
settingsMap.put(alias, settings);
}
return settings;
}
}
protected void settingsChanged(String settingsAlias, LoginovSettings settingsObject) {
synchronized (locker) {
settingsMap.put(settingsAlias, settingsObject);
if (settingsAccess != null) {
settingsAccess.setSettings(settingsAlias, INDICATOR_NAME, settingsObject);
}
}
}
@Override
public void acceptSettingsInterface(SettingsAccess settingsAccess) {
this.settingsAccess = settingsAccess;
}
@Override
public StrategyPanel[] getCustomGuiFor(String alias, String indicatorName) {
if (!INDICATOR_NAME.equals(indicatorName)) {
return new StrategyPanel;
}
StrategyPanel panel = new StrategyPanel("Loginov Settings", new GridBagLayout());
GridBagConstraints gbc = new GridBagConstraints();
gbc.insets = new Insets(5, 5, 5, 5);
gbc.fill = GridBagConstraints.HORIZONTAL;
gbc.weightx = 1.0;
gbc.gridx = 0;
gbc.gridy = 0;
gbc.gridwidth = 2;
LoginovSettings currentSettings = getSettingsFor(alias);
ColorsConfigItem colorItem = new ColorsConfigItem(LINE_COLOR_NAME, LINE_COLOR_NAME, true,
currentSettings.lineColor, panel, new ColorsChangedListener() {
@Override
public void colorsChanged() {
InvalidateInterface inv = invalidateInterfaceMap.get(alias);
if (inv != null) {
inv.invalidate();
}
}
@Override
public void setColor(String name, Color color) {
LoginovSettings s = getSettingsFor(alias);
s.lineColor = color;
settingsChanged(alias, s);
}
});
panel.add(colorItem, gbc);
gbc.gridy++;
gbc.gridwidth = 1;
gbc.weightx = 0.4;
panel.add(new JLabel("Smoothing Alpha (0.01 - 1.0):"), gbc);
gbc.gridx = 1;
gbc.weightx = 0.6;
JSpinner alphaSpinner = new JSpinner(new SpinnerNumberModel(currentSettings.alpha, 0.01, 1.0, 0.05));
alphaSpinner.addChangeListener(e -> {
LoginovSettings s = getSettingsFor(alias);
s.alpha = ((Number) alphaSpinner.getValue()).doubleValue();
settingsChanged(alias, s);
rebuildGenerator();
InvalidateInterface inv = invalidateInterfaceMap.get(alias);
if (inv != null) {
inv.invalidate();
}
});
panel.add(alphaSpinner, gbc);
gbc.gridy++;
gbc.gridx = 0;
gbc.weightx = 0.4;
panel.add(new JLabel("Line Width:"), gbc);
gbc.gridx = 1;
gbc.weightx = 0.6;
JSpinner widthSpinner = new JSpinner(new SpinnerNumberModel(currentSettings.lineWidth, 1, 10, 1));
widthSpinner.addChangeListener(e -> {
LoginovSettings s = getSettingsFor(alias);
s.lineWidth = ((Number) widthSpinner.getValue()).intValue();
settingsChanged(alias, s);
provider.sendUserMessage(Layer1ApiUserMessageModifyIndicator.builder(
Loginov.class, INDICATOR_NAME)
.setIsAdd(true)
.setLineWidth(s.lineWidth)
.build());
});
panel.add(widthSpinner, gbc);
return new StrategyPanel[] { panel };
}
@Override
public void setColor(String alias, String name, Color color) {
LoginovSettings settings = getSettingsFor(alias);
settings.lineColor = color;
settingsChanged(alias, settings);
}
@Override
public Color getColor(String alias, String name) {
return getSettingsFor(alias).lineColor;
}
@Override
public void addColorChangeListener(ColorsChangedListener listener) {
}
}
