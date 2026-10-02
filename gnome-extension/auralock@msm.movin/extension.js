/*
 * AuraLock Biometric Lock Screen Extension (msm.movin)
 * Displays glowing biometric indicator & AuraLock status on the Linux Lock Screen.
 */

import St from 'gi://St';
import Clutter from 'gi://Clutter';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import { Extension } from 'resource:///org/gnome/shell/extensions/extension.js';

export default class AuraLockExtension extends Extension {
    enable() {
        this._widget = null;
        this._hookLockScreen();
    }

    disable() {
        this._removeWidget();
    }

    _hookLockScreen() {
        if (!Main.screenShield) return;

        // Hook into Unlock Dialog
        this._origCreateUnlockDialog = Main.screenShield._createUnlockDialog;
        const self = this;

        Main.screenShield._createUnlockDialog = function() {
            const dialog = self._origCreateUnlockDialog.apply(this, arguments);
            self._injectWidget(dialog);
            return dialog;
        };

        // If unlock dialog is already active
        if (Main.screenShield._dialog) {
            this._injectWidget(Main.screenShield._dialog);
        }
    }

    _injectWidget(dialog) {
        if (!dialog || this._widget) return;

        const container = new St.BoxLayout({
            style_class: 'auralock-lockscreen-box',
            vertical: false,
            x_align: Clutter.ActorAlign.CENTER,
            y_align: Clutter.ActorAlign.CENTER
        });

        // Icon
        const iconFile = Gio.File.new_for_path(this.path + '/icons/auralock-fingerprint.svg');
        const gicon = new Gio.FileIcon({ file: iconFile });
        const icon = new St.Icon({
            gicon: gicon,
            style_class: 'auralock-icon-pulse'
        });
        container.add_child(icon);

        // Text box
        const textBox = new St.BoxLayout({
            vertical: true,
            x_align: Clutter.ActorAlign.START
        });

        const title = new St.Label({
            text: 'AuraLock Biometric Key',
            style_class: 'auralock-title'
        });
        const subtitle = new St.Label({
            text: '📱 Touch paired phone fingerprint to unlock',
            style_class: 'auralock-subtitle'
        });

        textBox.add_child(title);
        textBox.add_child(subtitle);
        container.add_child(textBox);

        this._widget = container;

        // Add above or inside prompt box
        if (dialog._promptBox) {
            dialog._promptBox.insert_child_at_index(container, 0);
        } else if (dialog.actor) {
            dialog.actor.add_child(container);
        }
    }

    _removeWidget() {
        if (this._widget) {
            this._widget.destroy();
            this._widget = null;
        }
        if (this._origCreateUnlockDialog && Main.screenShield) {
            Main.screenShield._createUnlockDialog = this._origCreateUnlockDialog;
            this._origCreateUnlockDialog = null;
        }
    }
}
