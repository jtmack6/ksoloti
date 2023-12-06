/**
 * Copyright (C) 2013, 2014 Johannes Taelman
 *
 * This file is part of Axoloti.
 *
 * Axoloti is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Axoloti is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Axoloti. If not, see <http://www.gnu.org/licenses/>.
 */
package axoloti.utils;

import java.awt.Color;
import java.awt.Font;

import javax.swing.UIManager;

// import java.io.InputStream;

/**
 *
 * @author Johannes Taelman
 */
public class Constants {

    // public static final Font FONT = new Font("SansSerif", Font.PLAIN, 10);
    // public static final Font FONT = new Font("monospaced", Font.PLAIN, 9);
    // public static final Font FONT = UIManager.getFont("mini.font").deriveFont(Font.BOLD, 9.0f);
    public static final Font FONT = UIManager.getFont("mini.font").deriveFont(10.0f);
    public static final Font FONT_BOLD = FONT.deriveFont(Font.BOLD);

    public static final int X_GRID = 14;
    public static final int Y_GRID = 14;

    // public static final Color TRANSPARENT = new Color(255, 255, 255, 0);

    public static final int PATCH_SIZE = 5000;

    public static final String OBJECT_LAYER_PANEL = "OBJECT_LAYER_PANEL";
    public static final String DRAGGED_OBJECT_LAYER_PANEL = "DRAGGED_OBJECT_LAYER_PANEL";

    public static final int ANCESTOR_CACHE_SIZE = 1024;
}