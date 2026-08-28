import { Pipe, PipeTransform } from '@angular/core';

/** Port of Python inr() Jinja2 filter — Indian comma grouping */
@Pipe({ name: 'inr', standalone: true })
export class InrPipe implements PipeTransform {
  transform(value: number | string | null | undefined, autoscale = false): string {
    const v = parseFloat(String(value ?? 0).replace(/,/g, '').replace(/₹/g, '').replace(/%/g, ''));
    if (isNaN(v)) return '0';
    if (autoscale) {
      const av = Math.abs(v);
      if (av >= 1e7) return `${(v / 1e7).toFixed(2)} Cr`;
      if (av >= 1e5) return `${(v / 1e5).toFixed(2)} L`;
    }
    const neg = v < 0;
    const abs = Math.abs(v);
    const str = abs.toFixed(2).replace(/\.?0+$/, '');
    const [whole, dec] = str.split('.');
    let w = whole;
    if (w.length > 3) {
      const last3 = w.slice(-3);
      let rest = w.slice(0, -3);
      const parts: string[] = [];
      while (rest.length > 2) { parts.unshift(rest.slice(-2)); rest = rest.slice(0, -2); }
      if (rest) parts.unshift(rest);
      w = parts.join(',') + ',' + last3;
    }
    return (neg ? '−' : '') + w + (dec ? '.' + dec : '');
  }
}

/** Port of Python pct() filter */
@Pipe({ name: 'pct', standalone: true })
export class PctPipe implements PipeTransform {
  transform(value: number | string | null | undefined): string {
    const v = parseFloat(String(value ?? 0));
    return isNaN(v) ? '0.00%' : `${v.toFixed(2)}%`;
  }
}

/** Color class for P&L values */
@Pipe({ name: 'pnlClass', standalone: true })
export class PnlClassPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    if (!value) return 'text-gray-400';
    return value > 0 ? 'text-green-400' : 'text-red-400';
  }
}
