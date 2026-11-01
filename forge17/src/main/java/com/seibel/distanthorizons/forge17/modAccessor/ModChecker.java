/*
 * This file is part of the Distant Horizons mod
 * licensed under the GNU LGPL v3 License.
 * Copyright (C) 2020 James Seibel
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.forge17.modAccessor;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModChecker;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

public class ModChecker implements IModChecker 
{

    public static final ModChecker INSTANCE = new ModChecker();
	
    @Override
    public boolean isModLoaded(String modid) { return Loader.isModLoaded(modid); }
	
    @Override
    public File modLocation(String modid) 
    {
        Stream<ModContainer> foundStream = Loader.instance()
            .getModList()
            .stream()
            .filter(
                x -> x.getModId()
                    .equals(modid));
        Optional<ModContainer> container = foundStream.findFirst();
        if (!container.isPresent()) {
            throw new RuntimeException("Mod not found: " + modid);
        }
        return container.get()
            .getSource();
    }

}
