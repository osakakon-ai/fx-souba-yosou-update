(() => {
  'use strict';

  const APP_VERSION = '0.1.0';
  const STORAGE_KEY = 'kon_training_app_v1';
  const ACTIVE_KEY = 'kon_training_active_v1';
  const $ = id => document.getElementById(id);

  const defaultMenus = [
    { id: cryptoId(), name: 'スクワット', weight: 20, reps: 10, sets: 3, interval: 60 },
    { id: cryptoId(), name: '腕立て伏せ', weight: 0, reps: 12, sets: 3, interval: 45 },
    { id: cryptoId(), name: 'ダンベルカール', weight: 8, reps: 10, sets: 3, interval: 60 }
  ];

  let state = loadState();
  let activeWorkout = loadActiveWorkout();
  let calendarCursor = new Date();
  let restTimerId = null;
  let deferredInstallPrompt = null;
  let shareBlob = null;
  let swRegistration = null;

  function cryptoId() {
    if (window.crypto?.randomUUID) return crypto.randomUUID();
    return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return { version: APP_VERSION, menus: defaultMenus, logs: {} };
      const parsed = JSON.parse(raw);
      return {
        version: APP_VERSION,
        menus: Array.isArray(parsed.menus) ? parsed.menus : defaultMenus,
        logs: parsed.logs && typeof parsed.logs === 'object' ? parsed.logs : {}
      };
    } catch {
      return { version: APP_VERSION, menus: defaultMenus, logs: {} };
    }
  }

  function saveState() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }

  function loadActiveWorkout() {
    try {
      const raw = localStorage.getItem(ACTIVE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch { return null; }
  }

  function saveActiveWorkout() {
    if (!activeWorkout) localStorage.removeItem(ACTIVE_KEY);
    else localStorage.setItem(ACTIVE_KEY, JSON.stringify(activeWorkout));
  }

  function localDateKey(date = new Date()) {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }

  function formatDateJP(key) {
    const [y,m,d] = key.split('-').map(Number);
    return `${y}年${m}月${d}日`;
  }

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
  }

  function showToast(message) {
    const toast = $('toast');
    toast.textContent = message;
    toast.classList.add('show');
    clearTimeout(showToast.timer);
    showToast.timer = setTimeout(() => toast.classList.remove('show'), 2200);
  }

  function switchView(viewId) {
    document.querySelectorAll('.view').forEach(v => v.classList.toggle('active', v.id === viewId));
    document.querySelectorAll('.nav-btn').forEach(b => b.classList.toggle('active', b.dataset.view === viewId));
    if (viewId === 'calendarView') renderCalendar();
    if (viewId === 'menusView') renderMenus();
    if (viewId === 'workoutView') renderWorkout();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  document.querySelectorAll('.nav-btn').forEach(btn => btn.addEventListener('click', () => switchView(btn.dataset.view)));

  function renderCalendar() {
    const year = calendarCursor.getFullYear();
    const month = calendarCursor.getMonth();
    $('monthTitle').textContent = `${year}年 ${month + 1}月`;
    const grid = $('calendarGrid');
    grid.innerHTML = '';
    const first = new Date(year, month, 1);
    const days = new Date(year, month + 1, 0).getDate();
    const todayKey = localDateKey();

    for (let i = 0; i < first.getDay(); i++) {
      const empty = document.createElement('div');
      empty.className = 'calendar-day empty';
      grid.appendChild(empty);
    }

    for (let day = 1; day <= days; day++) {
      const date = new Date(year, month, day);
      const key = localDateKey(date);
      const log = state.logs[key];
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `calendar-day${key === todayKey ? ' today' : ''}${log ? ' has-log' : ''}`;
      let summary = '';
      if (log) {
        const names = log.exercises.map(e => e.name);
        summary = `<div class="day-log"><strong>${names.length}種目</strong>${escapeHtml(names.slice(0,2).join('・'))}${names.length > 2 ? ` +${names.length-2}` : ''}</div>`;
        button.addEventListener('click', () => openLogDetail(key));
      }
      button.innerHTML = `<span class="day-number">${day}</span>${summary}`;
      grid.appendChild(button);
    }
  }

  $('prevMonthBtn').addEventListener('click', () => {
    calendarCursor = new Date(calendarCursor.getFullYear(), calendarCursor.getMonth() - 1, 1);
    renderCalendar();
  });
  $('nextMonthBtn').addEventListener('click', () => {
    calendarCursor = new Date(calendarCursor.getFullYear(), calendarCursor.getMonth() + 1, 1);
    renderCalendar();
  });
  $('todayBtn').addEventListener('click', () => {
    calendarCursor = new Date();
    renderCalendar();
  });

  function openLogDetail(key) {
    const log = state.logs[key];
    if (!log) return;
    $('detailTitle').textContent = formatDateJP(key);
    $('detailBody').innerHTML = `
      <div class="stack">
        ${log.exercises.map(e => `<div class="detail-item"><strong>${escapeHtml(e.name)}</strong><span>${e.weight}kg × ${e.reps}回 × ${e.sets}セット / 休憩 ${e.interval}秒</span></div>`).join('')}
      </div>
      <div class="dialog-actions"><button id="detailShareBtn" class="primary-btn" type="button">カレンダー＋メニューを共有</button></div>`;
    $('detailDialog').showModal();
    $('detailShareBtn').addEventListener('click', () => openShareDialog(key));
  }
  $('detailCloseBtn').addEventListener('click', () => $('detailDialog').close());

  function renderMenus() {
    const list = $('menuList');
    if (!state.menus.length) {
      list.innerHTML = `<div class="card empty-state"><strong>登録メニューはまだありません</strong>上のフォームから追加してください。</div>`;
      return;
    }
    list.innerHTML = state.menus.map(menu => `
      <div class="card menu-card">
        <div class="menu-top">
          <div>
            <div class="menu-name">${escapeHtml(menu.name)}</div>
            <div class="menu-meta">${menu.weight}kg × ${menu.reps}回 × ${menu.sets}セット ・ 休憩${menu.interval}秒</div>
          </div>
          <div class="row-actions">
            <button class="small-btn" data-action="edit" data-id="${menu.id}" type="button">編集</button>
            <button class="small-btn" data-action="delete" data-id="${menu.id}" type="button">削除</button>
          </div>
        </div>
      </div>`).join('');

    list.querySelectorAll('[data-action="edit"]').forEach(btn => btn.addEventListener('click', () => editMenu(btn.dataset.id)));
    list.querySelectorAll('[data-action="delete"]').forEach(btn => btn.addEventListener('click', () => deleteMenu(btn.dataset.id)));
  }

  $('menuForm').addEventListener('submit', event => {
    event.preventDefault();
    const item = {
      id: $('menuId').value || cryptoId(),
      name: $('menuName').value.trim(),
      weight: Number($('menuWeight').value),
      reps: Number($('menuReps').value),
      sets: Number($('menuSets').value),
      interval: Number($('menuInterval').value)
    };
    if (!item.name) return;
    const index = state.menus.findIndex(m => m.id === item.id);
    if (index >= 0) state.menus[index] = item; else state.menus.push(item);
    saveState();
    resetMenuForm();
    renderMenus();
    showToast(index >= 0 ? 'メニューを更新しました' : 'メニューを追加しました');
  });

  function editMenu(id) {
    const menu = state.menus.find(m => m.id === id);
    if (!menu) return;
    $('menuId').value = menu.id;
    $('menuName').value = menu.name;
    $('menuWeight').value = menu.weight;
    $('menuReps').value = menu.reps;
    $('menuSets').value = menu.sets;
    $('menuInterval').value = menu.interval;
    $('menuCancelBtn').classList.remove('hidden');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function deleteMenu(id) {
    const menu = state.menus.find(m => m.id === id);
    if (!menu) return;
    if (!confirm(`「${menu.name}」を削除しますか？`)) return;
    state.menus = state.menus.filter(m => m.id !== id);
    saveState();
    renderMenus();
  }

  function resetMenuForm() {
    $('menuForm').reset();
    $('menuId').value = '';
    $('menuWeight').value = 20;
    $('menuReps').value = 10;
    $('menuSets').value = 3;
    $('menuInterval').value = 60;
    $('menuCancelBtn').classList.add('hidden');
  }
  $('menuCancelBtn').addEventListener('click', resetMenuForm);

  function renderWorkout() {
    clearInterval(restTimerId);
    restTimerId = null;
    if (activeWorkout) {
      if (activeWorkout.phase === 'planning') return renderPlanning();
      if (activeWorkout.phase === 'active') return renderActiveSession();
      if (activeWorkout.phase === 'finished') return renderFinished(activeWorkout.date);
    }
    renderWorkoutPicker();
  }

  function renderWorkoutPicker() {
    const host = $('workoutContent');
    if (!state.menus.length) {
      host.innerHTML = `<div class="card empty-state"><strong>先に筋トレメニューを登録してください</strong>下の「メニュー」から登録できます。</div>`;
      return;
    }
    host.innerHTML = `
      <div class="stack" id="workoutChoices">
        ${state.menus.map(menu => `
          <label class="card choice-card">
            <input type="checkbox" value="${menu.id}" />
            <span class="choice-body">
              <span class="choice-title">${escapeHtml(menu.name)}</span>
              <span class="choice-meta">${menu.weight}kg × ${menu.reps}回 × ${menu.sets}セット ・ 休憩${menu.interval}秒</span>
            </span>
          </label>`).join('')}
      </div>
      <div class="sticky-action"><button id="createPlanBtn" class="primary-btn" type="button" disabled>選択したメニューで開始</button></div>`;

    const checks = [...host.querySelectorAll('input[type="checkbox"]')];
    const startBtn = $('createPlanBtn');
    checks.forEach(c => c.addEventListener('change', () => startBtn.disabled = !checks.some(x => x.checked)));
    startBtn.addEventListener('click', () => {
      const selectedIds = checks.filter(c => c.checked).map(c => c.value);
      const exercises = selectedIds.map(id => state.menus.find(m => m.id === id)).filter(Boolean).map(m => ({...m, completedSets: []}));
      activeWorkout = {
        id: cryptoId(), date: localDateKey(), createdAt: Date.now(), startedAt: null,
        phase: 'planning', exercises, currentExercise: 0, currentSet: 1,
        resting: false, restEndAt: null, pendingAdvance: null
      };
      saveActiveWorkout();
      renderWorkout();
    });
  }

  function renderPlanning() {
    const host = $('workoutContent');
    host.innerHTML = `
      <div class="card plan-card">
        <h2>今日の順番</h2>
        <p class="hint">↑↓で自由に並べ替えできます。</p>
        <div id="planList">
          ${activeWorkout.exercises.map((e, i) => `
            <div class="plan-item">
              <div><strong>${i+1}. ${escapeHtml(e.name)}</strong><div class="menu-meta">${e.weight}kg × ${e.reps}回 × ${e.sets}セット ・ 休憩${e.interval}秒</div></div>
              <div class="order-actions">
                <button type="button" data-move="up" data-index="${i}" ${i===0?'disabled':''}>↑</button>
                <button type="button" data-move="down" data-index="${i}" ${i===activeWorkout.exercises.length-1?'disabled':''}>↓</button>
              </div>
            </div>`).join('')}
        </div>
        <div class="session-actions" style="margin-top:14px">
          <button id="beginWorkoutBtn" class="primary-btn" type="button">トレーニング開始</button>
          <button id="cancelPlanBtn" class="ghost-btn" type="button">選び直す</button>
        </div>
      </div>`;

    host.querySelectorAll('[data-move]').forEach(btn => btn.addEventListener('click', () => {
      const i = Number(btn.dataset.index);
      const j = btn.dataset.move === 'up' ? i - 1 : i + 1;
      if (j < 0 || j >= activeWorkout.exercises.length) return;
      [activeWorkout.exercises[i], activeWorkout.exercises[j]] = [activeWorkout.exercises[j], activeWorkout.exercises[i]];
      saveActiveWorkout();
      renderPlanning();
    }));

    $('beginWorkoutBtn').addEventListener('click', () => {
      activeWorkout.phase = 'active';
      activeWorkout.startedAt = Date.now();
      saveActiveWorkout();
      renderWorkout();
    });
    $('cancelPlanBtn').addEventListener('click', () => {
      activeWorkout = null; saveActiveWorkout(); renderWorkout();
    });
  }

  function renderActiveSession() {
    const e = activeWorkout.exercises[activeWorkout.currentExercise];
    if (!e) return finishWorkout();
    if (activeWorkout.resting) return renderRest(e);

    const host = $('workoutContent');
    host.innerHTML = `
      <div class="card session-card">
        <div class="session-progress">種目 ${activeWorkout.currentExercise + 1} / ${activeWorkout.exercises.length}</div>
        <div class="exercise-title">${escapeHtml(e.name)}</div>
        <div class="exercise-prescription">
          <span class="pill">${e.weight} kg</span><span class="pill">${e.reps} 回</span><span class="pill">${e.sets} セット</span><span class="pill">休憩 ${e.interval} 秒</span>
        </div>
        <div class="set-dots">${Array.from({length:e.sets}, (_,i) => `<span class="set-dot ${i < e.completedSets.length ? 'done' : i === e.completedSets.length ? 'current' : ''}">${i+1}</span>`).join('')}</div>
        <div class="session-actions">
          <button id="completeSetBtn" class="primary-btn" type="button">${e.completedSets.length + 1}セット目 完了</button>
          <button id="endWorkoutBtn" class="danger-btn" type="button">筋トレを中断</button>
        </div>
        ${renderQueuePreview()}
      </div>`;

    $('completeSetBtn').addEventListener('click', completeCurrentSet);
    $('endWorkoutBtn').addEventListener('click', abortWorkout);
  }

  function renderQueuePreview() {
    return `<div class="queue-preview"><div class="queue-preview-title">今日のメニュー</div>${activeWorkout.exercises.map((x,i) => `<div class="queue-row ${i===activeWorkout.currentExercise?'current':''}"><span>${i+1}. ${escapeHtml(x.name)}</span><span>${x.completedSets.length}/${x.sets}</span></div>`).join('')}</div>`;
  }

  function completeCurrentSet() {
    const e = activeWorkout.exercises[activeWorkout.currentExercise];
    const setNo = e.completedSets.length + 1;
    e.completedSets.push({ setNo, weight: e.weight, reps: e.reps, doneAt: Date.now() });

    const exerciseDone = e.completedSets.length >= e.sets;
    const allDone = exerciseDone && activeWorkout.currentExercise >= activeWorkout.exercises.length - 1;
    if (allDone) {
      saveActiveWorkout();
      finishWorkout();
      return;
    }

    activeWorkout.pendingAdvance = exerciseDone ? 'exercise' : 'set';
    activeWorkout.resting = true;
    activeWorkout.restEndAt = Date.now() + Math.max(0, Number(e.interval)) * 1000;
    saveActiveWorkout();
    if (e.interval <= 0) finishRest(); else renderWorkout();
  }

  function renderRest(e) {
    const host = $('workoutContent');
    host.innerHTML = `
      <div class="card rest-panel">
        <div class="rest-label">インターバル</div>
        <div id="restTime" class="rest-time">00:00</div>
        <div class="hint">次：${activeWorkout.pendingAdvance === 'exercise' ? escapeHtml(activeWorkout.exercises[activeWorkout.currentExercise + 1]?.name || '') : `${e.completedSets.length + 1}セット目`}</div>
        <div class="rest-adjust">
          <button id="minus15Btn" class="small-btn" type="button">−15秒</button>
          <input id="restSecondsInput" type="number" min="0" max="3600" step="5" value="${Math.max(0, Math.ceil((activeWorkout.restEndAt - Date.now())/1000))}" aria-label="残り休憩秒数" />
          <button id="plus15Btn" class="small-btn" type="button">＋15秒</button>
        </div>
        <div class="session-actions"><button id="skipRestBtn" class="primary-btn" type="button">休憩を終える</button></div>
        ${renderQueuePreview()}
      </div>`;

    const update = () => {
      const remain = Math.max(0, Math.ceil((activeWorkout.restEndAt - Date.now()) / 1000));
      const mm = String(Math.floor(remain / 60)).padStart(2, '0');
      const ss = String(remain % 60).padStart(2, '0');
      $('restTime').textContent = `${mm}:${ss}`;
      $('restSecondsInput').value = remain;
      if (remain <= 0) finishRest();
    };
    update();
    restTimerId = setInterval(update, 250);

    $('minus15Btn').addEventListener('click', () => adjustRest(-15));
    $('plus15Btn').addEventListener('click', () => adjustRest(15));
    $('restSecondsInput').addEventListener('change', event => {
      activeWorkout.restEndAt = Date.now() + Math.max(0, Number(event.target.value) || 0) * 1000;
      saveActiveWorkout();
    });
    $('skipRestBtn').addEventListener('click', finishRest);
  }

  function adjustRest(delta) {
    const remain = Math.max(0, Math.ceil((activeWorkout.restEndAt - Date.now()) / 1000));
    activeWorkout.restEndAt = Date.now() + Math.max(0, remain + delta) * 1000;
    saveActiveWorkout();
  }

  function finishRest() {
    clearInterval(restTimerId);
    restTimerId = null;
    if (!activeWorkout) return;
    if (activeWorkout.pendingAdvance === 'exercise') activeWorkout.currentExercise += 1;
    activeWorkout.resting = false;
    activeWorkout.restEndAt = null;
    activeWorkout.pendingAdvance = null;
    saveActiveWorkout();
    renderWorkout();
  }

  function abortWorkout() {
    if (!confirm('今日の筋トレを中断しますか？ 完了していない内容は記録されません。')) return;
    clearInterval(restTimerId);
    activeWorkout = null;
    saveActiveWorkout();
    renderWorkout();
  }

  function finishWorkout() {
    clearInterval(restTimerId);
    const date = activeWorkout.date || localDateKey();
    const log = {
      date,
      startedAt: activeWorkout.startedAt,
      finishedAt: Date.now(),
      exercises: activeWorkout.exercises.map(e => ({...e}))
    };
    state.logs[date] = log;
    saveState();
    activeWorkout = { phase: 'finished', date };
    saveActiveWorkout();
    renderFinished(date);
  }

  function renderFinished(date) {
    const log = state.logs[date];
    const host = $('workoutContent');
    host.innerHTML = `
      <div class="card finish-card">
        <div class="finish-icon">✓</div>
        <h2>今日の筋トレ完了</h2>
        <p class="hint">カレンダーにも記録しました。</p>
        <div class="stack">${log.exercises.map(e => `<div class="detail-item"><strong>${escapeHtml(e.name)}</strong><span>${e.weight}kg × ${e.reps}回 × ${e.sets}セット</span></div>`).join('')}</div>
        <div class="session-actions" style="margin-top:14px">
          <button id="finishShareBtn" class="primary-btn" type="button">カレンダー＋今日のメニューをXへ共有</button>
          <button id="finishDoneBtn" class="ghost-btn" type="button">完了</button>
        </div>
      </div>`;
    $('finishShareBtn').addEventListener('click', () => openShareDialog(date));
    $('finishDoneBtn').addEventListener('click', () => {
      activeWorkout = null; saveActiveWorkout(); switchView('calendarView');
    });
  }

  async function openShareDialog(date) {
    const log = state.logs[date];
    if (!log) return;
    drawShareCanvas(date, log);
    shareBlob = await new Promise(resolve => $('shareCanvas').toBlob(resolve, 'image/png', .96));
    if ($('detailDialog').open) $('detailDialog').close();
    $('shareDialog').showModal();
  }

  function drawShareCanvas(dateKey, log) {
    const canvas = $('shareCanvas');
    const ctx = canvas.getContext('2d');
    const rows = Math.max(1, log.exercises.length);
    canvas.height = Math.max(1350, 920 + rows * 115);
    const W = canvas.width;
    const H = canvas.height;
    ctx.fillStyle = '#0b0f14'; ctx.fillRect(0,0,W,H);
    ctx.fillStyle = '#f5f7fa'; ctx.font = '900 56px system-ui, sans-serif'; ctx.fillText('筋トレログ', 64, 92);
    ctx.fillStyle = '#8d9aaa'; ctx.font = '600 30px system-ui, sans-serif'; ctx.fillText(formatDateJP(dateKey), 64, 140);

    const [y,m,d] = dateKey.split('-').map(Number);
    ctx.fillStyle = '#111821'; roundRect(ctx, 54, 190, W-108, 510, 30, true);
    ctx.fillStyle = '#f5f7fa'; ctx.font = '900 38px system-ui, sans-serif'; ctx.fillText(`${y}年 ${m}月`, 84, 246);
    const weekdays = ['日','月','火','水','木','金','土'];
    const left = 76, top = 290, cellW = (W-152)/7, cellH = 62;
    ctx.font = '700 23px system-ui, sans-serif';
    weekdays.forEach((x,i) => { ctx.fillStyle = '#8d9aaa'; ctx.textAlign='center'; ctx.fillText(x, left + cellW*i + cellW/2, top); });
    const first = new Date(y,m-1,1).getDay();
    const days = new Date(y,m,0).getDate();
    for (let day=1; day<=days; day++) {
      const pos = first + day - 1;
      const col = pos % 7, row = Math.floor(pos/7);
      const cx = left + cellW*col + cellW/2;
      const cy = top + 46 + cellH*row;
      const k = localDateKey(new Date(y,m-1,day));
      if (day === d) {
        ctx.beginPath(); ctx.arc(cx, cy-8, 24, 0, Math.PI*2); ctx.fillStyle='#57d68d'; ctx.fill();
        ctx.fillStyle='#07110b';
      } else { ctx.fillStyle='#dfe7ef'; }
      ctx.font = '800 22px system-ui, sans-serif'; ctx.textAlign='center'; ctx.fillText(String(day), cx, cy);
      if (state.logs[k] && day !== d) { ctx.beginPath(); ctx.arc(cx, cy+14, 4, 0, Math.PI*2); ctx.fillStyle='#57d68d'; ctx.fill(); }
    }
    ctx.textAlign='left';

    const blockY = 740;
    ctx.fillStyle = '#f5f7fa'; ctx.font = '900 38px system-ui, sans-serif'; ctx.fillText('TODAY’S WORKOUT', 64, blockY);
    let yPos = blockY + 70;
    log.exercises.forEach((e,i) => {
      ctx.fillStyle = '#111821'; roundRect(ctx, 54, yPos-42, W-108, 92, 22, true);
      ctx.fillStyle = '#57d68d'; ctx.font = '900 28px system-ui, sans-serif'; ctx.fillText(String(i+1).padStart(2,'0'), 82, yPos+4);
      ctx.fillStyle = '#f5f7fa'; ctx.font = '800 29px system-ui, sans-serif'; ctx.fillText(truncateCanvas(ctx, e.name, 470), 145, yPos+4);
      ctx.textAlign='right'; ctx.fillStyle='#aeb9c6'; ctx.font='700 24px system-ui, sans-serif'; ctx.fillText(`${e.weight}kg × ${e.reps}回 × ${e.sets}`, W-80, yPos+4);
      ctx.textAlign='left';
      yPos += 110;
    });
    ctx.fillStyle='#8d9aaa'; ctx.font='600 22px system-ui, sans-serif'; ctx.fillText(`Muscle Training Log  •  v${APP_VERSION}`, 64, H-54);
  }

  function truncateCanvas(ctx, text, maxWidth) {
    let s = String(text);
    if (ctx.measureText(s).width <= maxWidth) return s;
    while (s.length && ctx.measureText(s + '…').width > maxWidth) s = s.slice(0,-1);
    return s + '…';
  }

  function roundRect(ctx, x, y, w, h, r, fill) {
    const rr = Math.min(r, w/2, h/2);
    ctx.beginPath(); ctx.moveTo(x+rr,y); ctx.arcTo(x+w,y,x+w,y+h,rr); ctx.arcTo(x+w,y+h,x,y+h,rr); ctx.arcTo(x,y+h,x,y,rr); ctx.arcTo(x,y,x+w,y,rr); ctx.closePath(); if (fill) ctx.fill();
  }

  $('shareCloseBtn').addEventListener('click', () => $('shareDialog').close());
  $('downloadShareBtn').addEventListener('click', () => {
    if (!shareBlob) return;
    const a = document.createElement('a');
    a.href = URL.createObjectURL(shareBlob);
    a.download = `筋トレ_${localDateKey()}.png`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  });

  $('shareToXBtn').addEventListener('click', async () => {
    if (!shareBlob) return;
    const file = new File([shareBlob], `筋トレ_${localDateKey()}.png`, { type: 'image/png' });
    try {
      if (navigator.canShare?.({files:[file]}) && navigator.share) {
        await navigator.share({ title: '今日の筋トレ', text: '今日の筋トレ記録', files: [file] });
      } else if (navigator.share) {
        await navigator.share({ title: '今日の筋トレ', text: '今日の筋トレ記録' });
        showToast('画像共有に非対応のため、画像は保存ボタンから添付してください');
      } else {
        window.open('https://x.com/intent/post?text=' + encodeURIComponent('今日の筋トレ記録'), '_blank', 'noopener');
        showToast('画像は「画像を保存」から添付してください');
      }
    } catch (err) {
      if (err?.name !== 'AbortError') showToast('共有を開始できませんでした');
    }
  });

  async function setupServiceWorker() {
    if (!('serviceWorker' in navigator)) return;
    try {
      swRegistration = await navigator.serviceWorker.register('./service-worker.js');
      navigator.serviceWorker.addEventListener('controllerchange', () => location.reload());
    } catch (e) {
      console.warn('Service worker registration failed', e);
    }
  }

  $('updateBtn').addEventListener('click', async () => {
    if (!swRegistration) {
      showToast('公開後のHTTPS環境で更新機能が有効になります');
      return;
    }
    try {
      showToast('更新を確認しています…');
      await swRegistration.update();
      if (swRegistration.waiting) {
        swRegistration.waiting.postMessage('SKIP_WAITING');
      } else {
        showToast(`最新です v${APP_VERSION}`);
      }
    } catch {
      showToast('更新確認に失敗しました');
    }
  });

  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    deferredInstallPrompt = event;
    $('installBtn').classList.remove('hidden');
  });

  $('installBtn').addEventListener('click', async () => {
    if (!deferredInstallPrompt) return;
    deferredInstallPrompt.prompt();
    await deferredInstallPrompt.userChoice;
    deferredInstallPrompt = null;
    $('installBtn').classList.add('hidden');
  });

  window.addEventListener('appinstalled', () => showToast('ホーム画面に追加しました'));

  renderCalendar();
  renderMenus();
  renderWorkout();
  setupServiceWorker();
})();
