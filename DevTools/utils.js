// === utils.js - Глобальные утилиты, локализация и темизация портала ===

document.addEventListener('DOMContentLoaded', function() {
    initGlobalTheme();
    initLocalization();
});

// === 1. ЛОКАЛИЗАЦИЯ (i18n) ===
function initLocalization() {
    const lang = localStorage.getItem('app_lang') || 'ru';
    applyLanguage(lang);
}

// Глобальная функция применения языка ко всем элементам с data-i18n
window.applyLanguage = function(lang, customDict = null) {
    let globalDict = customDict;

    // Если словарь не передан напрямую, берем из синхронного кэша
    if (!globalDict) {
        const dictStr = localStorage.getItem('portal_dictionary');
        if (dictStr) {
            try { globalDict = JSON.parse(dictStr); } catch(e) { console.error("Cache parse error", e); }
        }
    }

    const dict = globalDict ? (globalDict[lang] || globalDict['ru']) : null;

    // Автоматическая поддержка направления текста (RTL) для Арабского языка
    if (lang === 'ar') {
        document.documentElement.setAttribute('dir', 'rtl');
    } else {
        document.documentElement.setAttribute('dir', 'ltr');
    }

    if (!dict) return;

    // Мгновенный перевод всех размеченных элементов
    const elements = document.querySelectorAll('[data-i18n]');
    elements.forEach(el => {
        const key = el.getAttribute('data-i18n');
        if (dict[key]) {
            const tagName = el.tagName.toLowerCase();
            if (tagName === 'input' || tagName === 'textarea') {
                if (el.hasAttribute('placeholder')) {
                    el.setAttribute('placeholder', dict[key]);
                }
                if (el.type === 'button' || el.type === 'submit') {
                    el.value = dict[key];
                }
            } else {
                el.innerText = dict[key];
            }
        }
    });
};

// Глобальная функция для перевода JS-строк (alert, confirm, динамические данные)
window.t = function(key, defaultStr) {
    const lang = localStorage.getItem('app_lang') || 'ru';
    try {
        const dictStr = localStorage.getItem('portal_dictionary');
        if (dictStr) {
            const dict = JSON.parse(dictStr)[lang];
            if (dict && dict[key]) return dict[key];
        }
    } catch(e) {}
    return defaultStr;
};


// === 2. ГЛОБАЛЬНАЯ ТЕМАТИЗАЦИЯ ===
function initGlobalTheme() {
    const isDark = localStorage.getItem('global_dark_theme') === 'true';
    applyTheme(isDark);
}

window.applyTheme = function(isDark) {
    if (isDark) {
        document.body.classList.add('dark-theme');
    } else {
        document.body.classList.remove('dark-theme');
    }

    // Синхронизация темы для редактора CodeMirror (если он есть на странице)
    if (typeof CodeMirror !== 'undefined') {
        const cmElements = document.querySelectorAll('.CodeMirror');
        cmElements.forEach(el => {
            if (el.CodeMirror) {
                el.CodeMirror.setOption('theme', isDark ? 'dracula' : 'default');
            }
        });
    }

    // Рассылаем событие, чтобы графики (Chart.js) могли перерисоваться
    const event = new CustomEvent('themeChanged', { detail: { isDark: isDark } });
    window.dispatchEvent(event);
};


// === 3. ГЛОБАЛЬНАЯ СИСТЕМА УВЕДОМЛЕНИЙ ===
window.showNotification = function(message, isError = false) {
    let toast = document.getElementById('global-toast');

    if (!toast) {
        toast = document.createElement('div');
        toast.id = 'global-toast';
        Object.assign(toast.style, {
            position: 'fixed',
            bottom: '20px',
            right: '20px',
            padding: '12px 24px',
            borderRadius: '6px',
            fontWeight: 'bold',
            display: 'none',
            zIndex: '999999',
            boxShadow: '0 4px 6px rgba(0,0,0,0.1)',
            fontFamily: '"Segoe UI", sans-serif',
            fontSize: '14px',
            transition: 'opacity 0.3s ease'
        });
        document.body.appendChild(toast);
    }

    toast.style.backgroundColor = isError ? '#ef4444' : '#10b981';
    toast.style.color = '#ffffff';
    toast.innerText = message;

    toast.style.display = 'block';
    setTimeout(() => toast.style.opacity = '1', 10);

    if (window.toastTimeout) clearTimeout(window.toastTimeout);

    window.toastTimeout = setTimeout(() => {
        toast.style.opacity = '0';
        setTimeout(() => toast.style.display = 'none', 300);
    }, 2500);
};


// === 4. УНИВЕРСАЛЬНОЕ ЭКРАНИРОВАНИЕ HTML ===
window.escapeHtml = function(text) {
    if (!text) return "";
    return String(text)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
};