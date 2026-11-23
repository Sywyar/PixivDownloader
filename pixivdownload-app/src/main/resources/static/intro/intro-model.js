// 介绍页的纯计算部分：滚动进度、散落位置分配、吸附翻页、三态标签筛选。不访问 DOM。
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

  // 吸附翻页中视为同一位置的距离（像素）。
  var SNAP_EPSILON = 2;

  // 吸附翻页的停靠点。regions 为文档坐标中的区域 {top, height, free}：每个区域顶部是一个停靠点；
  // free 区域高于视口时，底部对齐视口的位置也是停靠点，两者之间可以自由滚动。页面两端总是停靠点。
  // 超出视口不足 3% 的部分只是区域底部的留白（留白不小于视口高度的 5%），不单独停靠，免得一次滚动只挪动几个像素。
  function snapLayout(regions, viewport, max) {
    var points = [0, max];
    var free = [];
    var minOverflow = Math.max(SNAP_EPSILON, viewport * 0.03);
    regions.forEach(function (region) {
      var top = Math.max(0, Math.min(region.top, max));
      points.push(top);
      if (!region.free) return;
      var bottom = Math.min(region.top + region.height - viewport, max);
      if (bottom - top > minOverflow) {
        points.push(bottom);
        free.push([top, bottom]);
      }
    });
    // 相距不足一小段视口的停靠点合并，避免多按一次只挪动几个像素；页面两端优先保留。
    var gap = Math.max(SNAP_EPSILON, viewport * 0.1);
    points.sort(function (a, b) { return a - b; });
    var stops = [];
    points.forEach(function (point) {
      var last = stops.length - 1;
      if (last < 0 || point - stops[last] > gap) stops.push(point);
      else if (point === max && stops[last] !== 0) stops[last] = max;
    });
    return { stops: stops, free: free };
  }

  // 包含 y 的自由滚动区间；不在任何区间内时返回 null。
  function freeRange(layout, y) {
    for (var i = 0; i < layout.free.length; i += 1) {
      var range = layout.free[i];
      if (y >= range[0] - SNAP_EPSILON && y <= range[1] + SNAP_EPSILON) return range;
    }
    return null;
  }

  // 自由区间内沿 direction 是否还有可滚动的余量。
  function hasRoom(range, y, direction) {
    return direction > 0 ? y < range[1] - SNAP_EPSILON : y > range[0] + SNAP_EPSILON;
  }

  // 从 y 出发、沿 direction（1 向下，-1 向上）的下一个停靠点；已到尽头时返回 null。
  function nextStop(layout, y, direction) {
    var stops = layout.stops;
    if (direction > 0) {
      for (var i = 0; i < stops.length; i += 1) if (stops[i] > y + SNAP_EPSILON) return stops[i];
    } else {
      for (var j = stops.length - 1; j >= 0; j -= 1) if (stops[j] < y - SNAP_EPSILON) return stops[j];
    }
    return null;
  }

  // 自行滚动（触摸、拖动滚动条等）停下后应回到的停靠点；位于停靠点或自由区间内时返回 null。
  // 从出发的停靠点沿 direction 走过两点间距的 commit 比例以上，就翻到前方的停靠点，否则退回。
  function settleStop(layout, y, direction, commit) {
    if (freeRange(layout, y)) return null;
    var before = nextStop(layout, y + SNAP_EPSILON + 1, -1);
    var after = nextStop(layout, y - SNAP_EPSILON - 1, 1);
    if (before === null || after === null) return before === null ? after : before;
    if (y - before <= SNAP_EPSILON || after - y <= SNAP_EPSILON) return null;
    if (!direction) return y - before <= after - y ? before : after;
    var origin = direction > 0 ? before : after;
    var ahead = direction > 0 ? after : before;
    return Math.abs(y - origin) / Math.abs(ahead - origin) > commit ? ahead : origin;
  }

  // 翻页动画时长（毫秒）：距离越远略长，但限制在可感知又不拖沓的范围内。
  function snapDuration(distance, viewport) {
    var screens = viewport > 0 ? Math.abs(distance) / viewport : 1;
    return Math.round(Math.min(1300, Math.max(800, 700 + 300 * Math.sqrt(screens))));
  }

  // 翻页缓动：前段平缓加速、后段长尾减速。
  function snapEase(t) {
    return t < 0.5 ? 8 * t * t * t * t : 1 - Math.pow(-2 * t + 2, 4) / 2;
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
    freeRange: freeRange,
    hasRoom: hasRoom,
    looseness: looseness,
    matchesTags: matchesTags,
    nextStop: nextStop,
    nextTagState: nextTagState,
    sceneState: sceneState,
    scrollProgress: scrollProgress,
    settleStop: settleStop,
    snapDuration: snapDuration,
    snapEase: snapEase,
    snapLayout: snapLayout
  };
})(typeof window !== 'undefined' ? window : globalThis);
