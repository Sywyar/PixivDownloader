// 介绍页的纯计算部分：滚动进度、散落位置分配、三态标签筛选。不访问 DOM。
(function (global) {
  'use strict';

  var TAG_STATES = ['ignore', 'include', 'exclude'];

  // 首屏各阶段在滚动进度（0–1）上的区间。
  var PHASES = {
    copy: [0, 0.2],
    assemble: [0.02, 0.5],
    stagger: 0.12,
    chrome: [0.34, 0.56],
    caption: [0.4, 0.58]
  };

  // 散落位置：瓦片中心相对视口的比例坐标、缩放和倾角，围绕标题留出中间区域。
  var WIDE_SLOTS = [
    [0.045, 0.2, 1.15, -7], [0.135, 0.47, 1.45, 5], [0.04, 0.75, 1, -4], [0.175, 0.89, 1.2, 8],
    [-0.012, 0.5, 0.8, 6], [0.955, 0.22, 1.2, 6], [0.865, 0.5, 1.5, -5], [0.965, 0.77, 0.95, 9],
    [0.83, 0.9, 1.25, -6], [1.012, 0.48, 0.8, -8], [0.3, 0.9, 1.05, -3], [0.43, 0.99, 0.9, 4],
    [0.57, 0.91, 1.15, -6], [0.7, 0.99, 0.95, 3], [0.19, 0.06, 0.7, 10], [0.81, 0.07, 0.75, -9],
    [0.36, 1.08, 0.8, 7], [0.64, 1.09, 0.8, -5]
  ];
  var NARROW_SLOTS = [
    [0.13, 0.76, 1, -6], [0.39, 0.73, 1.1, 4], [0.65, 0.77, 1, -3], [0.9, 0.74, 1.05, 7],
    [0.02, 0.92, 1, 5], [0.27, 0.94, 1.1, -5], [0.52, 0.92, 1.15, 3], [0.78, 0.95, 1, -7],
    [1.02, 0.9, 0.9, 6], [-0.06, 0.6, 0.8, 8], [1.06, 0.58, 0.8, -8], [0.5, 1.09, 0.9, 2]
  ];

  function clamp01(value) {
    return value > 0 ? Math.min(value, 1) : 0;
  }

  function span(progress, range) {
    return clamp01((progress - range[0]) / (range[1] - range[0]));
  }

  function easeInOut(t) {
    return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
  }

  // 滚动进度：区段顶部贴住视口时为 0，区段底部贴住视口底部时为 1。
  function scrollProgress(top, height, viewport) {
    var distance = height - viewport;
    return distance > 0 ? clamp01(-top / distance) : 1;
  }

  function sceneState(progress) {
    return {
      copy: 1 - easeInOut(span(progress, PHASES.copy)),
      chrome: easeInOut(span(progress, PHASES.chrome)),
      caption: easeInOut(span(progress, PHASES.caption))
    };
  }

  // 单个瓦片的散落程度：1 为完全散开，0 为回到网格。order 取 0–1，用来错开各瓦片的启动时刻。
  function looseness(progress, order) {
    var start = PHASES.assemble[0] + PHASES.stagger * clamp01(order);
    var end = PHASES.assemble[1] - PHASES.stagger * (1 - clamp01(order));
    return 1 - easeInOut(span(progress, [start, end]));
  }

  // 按距离就近为每个瓦片分配一个散落位置，减少飞行路径交叉。points 与返回值按瓦片顺序排列。
  function assignSlots(points, slots) {
    var free = slots.map(function (slot, index) { return index; });
    return points.map(function (point) {
      var best = 0;
      var bestDistance = Infinity;
      for (var i = 0; i < free.length; i += 1) {
        var slot = slots[free[i]];
        var dx = slot[0] - point[0];
        var dy = slot[1] - point[1];
        var distance = dx * dx + dy * dy;
        if (distance < bestDistance) {
          bestDistance = distance;
          best = i;
        }
      }
      return slots[free.splice(best, 1)[0]] || null;
    });
  }

  // 把与保留区重叠的矩形移到区外：向左、向右或向下，取位移最小的一种（上方是导航，不往上移）。
  // center 为矩形中心 [x, y]，half 为半宽半高 [w, h]，keep 为保留区 {left, top, right, bottom}。
  function clearOf(center, half, keep) {
    var overlaps = center[0] + half[0] > keep.left && center[0] - half[0] < keep.right
      && center[1] + half[1] > keep.top && center[1] - half[1] < keep.bottom;
    if (!overlaps) return center;
    var moves = [
      [keep.left - half[0], center[1]],
      [keep.right + half[0], center[1]],
      [center[0], keep.bottom + half[1]]
    ];
    return moves.reduce(function (best, move) {
      var cost = Math.abs(move[0] - center[0]) + Math.abs(move[1] - center[1]);
      var bestCost = Math.abs(best[0] - center[0]) + Math.abs(best[1] - center[1]);
      return cost < bestCost ? move : best;
    });
  }

  function nextTagState(state) {
    return TAG_STATES[(TAG_STATES.indexOf(state) + 1) % TAG_STATES.length];
  }

  // 作品需带有全部「包含」标签，且不带任何「排除」标签。
  function matchesTags(tags, states) {
    return Object.keys(states).every(function (tag) {
      var has = tags.indexOf(tag) !== -1;
      if (states[tag] === 'include') return has;
      if (states[tag] === 'exclude') return !has;
      return true;
    });
  }

  global.PixivIntroModel = {
    WIDE_SLOTS: WIDE_SLOTS,
    NARROW_SLOTS: NARROW_SLOTS,
    assignSlots: assignSlots,
    clearOf: clearOf,
    looseness: looseness,
    matchesTags: matchesTags,
    nextTagState: nextTagState,
    sceneState: sceneState,
    scrollProgress: scrollProgress
  };
})(typeof window !== 'undefined' ? window : globalThis);
