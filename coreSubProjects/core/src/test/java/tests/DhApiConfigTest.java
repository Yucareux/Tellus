/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package tests;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.core.config.api.DhApiConfigValue;
import com.seibel.distanthorizons.core.config.api.converters.RenderModeEnabledConverter;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import org.junit.Assert;
import org.junit.Test;

/**
 * Quick test to confirm API config handling works correctly.
 *
 * @author James Seibel
 * @version 2026-02-04
 */
public class DhApiConfigTest
{
	
	@Test
	public void ConfigTest()
	{
		ConfigEntry<EDhApiRendererMode> coreConfig = new ConfigEntry.Builder<EDhApiRendererMode>()
			.set(EDhApiRendererMode.DEBUG_TRIANGLE)
			.build();
		coreConfig.name = "test";
		
		DhApiConfigValue<EDhApiRendererMode, Boolean> apiConfig = new DhApiConfigValue<>(coreConfig, new RenderModeEnabledConverter());
		
		// start with no API value
		Assert.assertNull("API Value shouldn't be set yet", apiConfig.getApiValue());
		Assert.assertEquals("underlying config should be 'DEBUG'", EDhApiRendererMode.DEBUG_TRIANGLE, coreConfig.get());
		
		
		// set API value
		apiConfig.setValue(true);
		Assert.assertTrue("API Value should be 'true'", apiConfig.getApiValue());
		Assert.assertEquals("underlying config should be 'DEFAULT'", EDhApiRendererMode.DEFAULT, coreConfig.get());  
		
		// set API value again
		apiConfig.setValue(false);
		Assert.assertFalse("API Value should be 'false'", apiConfig.getApiValue());
		Assert.assertEquals("underlying config should be disabled", EDhApiRendererMode.DISABLED, coreConfig.get());
		
		// clear API value
		apiConfig.clearValue();
		Assert.assertNull("API Value should be null", apiConfig.getApiValue());
		Assert.assertEquals("underlying config should be 'DEBUG'", EDhApiRendererMode.DEBUG_TRIANGLE, coreConfig.get());
		
		
	}
	
}
