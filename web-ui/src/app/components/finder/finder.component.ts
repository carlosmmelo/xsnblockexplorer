import { Component, OnInit } from '@angular/core';
import { AbstractControl, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Observable } from 'rxjs/Observable';

import { TranslateService } from '@ngx-translate/core';

import { ErrorService } from '../../services/error.service';
import { NavigatorService } from '../../services/navigator.service';
import { AddressesService } from '../../services/addresses.service';
import { BlocksService } from '../../services/blocks.service';
import { TransactionsService } from '../../services/transactions.service';

const BLOCK_REGEX = '^[A-Fa-f0-9]{64}$';
const ADDRESS_REGEX = '^[a-zA-Z0-9]{34}$';

@Component({
  selector: 'app-finder',
  templateUrl: './finder.component.html',
  styleUrls: ['./finder.component.css']
})
export class FinderComponent implements OnInit {

  form: FormGroup;

  constructor(
    private formBuilder: FormBuilder,
    private navigatorService: NavigatorService,
    private addressesService: AddressesService,
    private blocksService: BlocksService,
    private transactionsService: TransactionsService,
    private translateService: TranslateService,
    public errorService: ErrorService) {

    this.createForm();
  }

  ngOnInit() {
  }

  private createForm() {
    this.form = this.formBuilder.group({
      searchField: [null, [Validators.required, Validators.pattern(`(${ADDRESS_REGEX})|(${BLOCK_REGEX})`)]],
    });
  }

  onSubmit() {
    const searchField = this.form.get('searchField').value;

    if (new RegExp(ADDRESS_REGEX).test(searchField)) {
      // address
      this.addressesService.get(searchField)
        .subscribe(
          response => this.navigatorService.addressDetails(searchField),
          response => this.errorService.renderServerErrors(this.form, response)
        );
    } else {
      // block or transaction
      this.transactionsService.get(searchField)
        .subscribe(
          response => this.navigatorService.transactionDetails(searchField),
          response => this.lookForBlock(searchField)
        );
    }
  }

  private lookForBlock(blockhash: string) {
    this.blocksService.get(blockhash)
      .subscribe(
        response => this.navigatorService.blockDetails(blockhash),
        response => this.onNothingFound()
      );
  }

  private onNothingFound() {
    this.translateService.get('error.nothingFound')
      .subscribe(msg => this.errorService.setFieldError(this.form, 'searchField', msg));
  }
}
