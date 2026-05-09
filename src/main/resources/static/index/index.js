(function() {
  // 检测 html-in-canvas 支持（CanvasRenderingContext2D.prototype.drawElementImage）
  var supported = typeof CanvasRenderingContext2D !== 'undefined' &&
                  typeof CanvasRenderingContext2D.prototype.drawElementImage === 'function';
  location.replace('/redirect?canvas=' + supported);
})();
