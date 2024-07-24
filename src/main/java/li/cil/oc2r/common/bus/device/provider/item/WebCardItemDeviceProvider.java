/* SPDX-License-Identifier: MIT */

package li.cil.oc2r.common.bus.device.provider.item;

import li.cil.oc2r.api.bus.device.ItemDevice;
import li.cil.oc2r.api.bus.device.provider.ItemDeviceQuery;
import li.cil.oc2r.api.capabilities.TerminalUserProvider;
import li.cil.oc2r.common.Config;
import li.cil.oc2r.common.bus.device.provider.util.AbstractItemDeviceProvider;
import li.cil.oc2r.common.bus.device.rpc.item.WebCardItemDevice;
import li.cil.oc2r.common.capabilities.Capabilities;
import li.cil.oc2r.common.item.Items;
import net.minecraftforge.common.util.LazyOptional;

import java.util.Optional;

public final class WebCardItemDeviceProvider extends AbstractItemDeviceProvider {
    public WebCardItemDeviceProvider() {
        super(Items.WEB_CARD);
    }

    ///////////////////////////////////////////////////////////////////

    @Override
    protected boolean matches(final ItemDeviceQuery query) {
        return super.matches(query) && getTerminalUserProvider(query).isPresent();
    }

    @Override
    protected Optional<ItemDevice> getItemDevice(final ItemDeviceQuery query) {
        return getTerminalUserProvider(query).map(provider ->
            new WebCardItemDevice(query.getItemStack(), provider));
    }

    @Override
    protected int getItemDeviceEnergyConsumption(final ItemDeviceQuery query) {
        return Config.webImportExportCardEnergyPerTick;
    }

    ///////////////////////////////////////////////////////////////////

    private Optional<TerminalUserProvider> getTerminalUserProvider(final ItemDeviceQuery query) {
        if (query.getContainerBlockEntity().isPresent()) {
            final LazyOptional<TerminalUserProvider> capability = query.getContainerBlockEntity().get()
                .getCapability(Capabilities.terminalUserProvider());
            if (capability.isPresent()) {
                return capability.resolve();
            }
        }

        if (query.getContainerEntity().isPresent()) {
            final LazyOptional<TerminalUserProvider> capability = query.getContainerEntity().get()
                .getCapability(Capabilities.terminalUserProvider());
            if (capability.isPresent()) {
                return capability.resolve();
            }
        }

        return Optional.empty();
    }
}
